package be.ntmn.inficam;

import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import be.ntmn.libinficam.SpatialCalibrationEngine;

/** Owns the spatial calibration state machine and transactional commit. */
final class CalibrationController implements AutoCloseable {
	enum State {
		IDLE, PREPARING, COLLECTING, VALIDATING, COMMITTING, COMPLETED,
		CANCELLED, FAILED
	}

	interface Listener {
		void onCalibrationState(State state, float progress, String detail);
	}

	static final long TOTAL_DURATION_NS = TimeUnit.MINUTES.toNanos(10);
	static final long STABILIZATION_DURATION_NS = TimeUnit.MINUTES.toNanos(2);
	private static final long FRAME_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(4);
	private static final long STATUS_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1);

	private final Object stateLock = new Object();
	private final Object commitLock = new Object();
	private final SpatialCalibrationEngine engine;
	private final SpatialCalibrationStore store;
	private final Listener listener;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r ->
			new Thread(r, "Spatial FPN calibration"));
	private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r ->
			new Thread(r, "Spatial FPN watchdog"));

	private volatile State state = State.IDLE;
	private volatile ThermalCameraHal camera;
	private volatile int width;
	private volatile int height;
	private volatile long startedNs;
	private volatile long lastFrameNs;
	private volatile long lastStatusNs;
	private volatile int operationToken;
	private volatile boolean shutterHeld;
	private volatile boolean profileLoaded;

	CalibrationController(SpatialCalibrationEngine engine, SpatialCalibrationStore store,
			Listener listener) {
		this.engine = engine;
		this.store = store;
		this.listener = listener;
		watchdog.scheduleWithFixedDelay(this::checkFrameTimeout, 1, 1, TimeUnit.SECONDS);
	}

	void attachCamera(ThermalCameraHal newCamera, int newWidth, int newHeight) {
		if (newCamera == null)
			throw new IllegalArgumentException("Camera HAL is required.");
		cancelInternal(State.CANCELLED, "Calibration replaced by a new camera session.");
		synchronized (stateLock) {
			camera = newCamera;
			width = newWidth;
			height = newHeight;
			operationToken++;
			state = State.IDLE;
			profileLoaded = false;
			if (!engine.configure(newWidth, newHeight))
				throw new IllegalStateException(engine.getLastError());
			try {
				SpatialCalibrationStore.Profile profile = store.load(
						newCamera.getPhysicalDeviceId(), newWidth, newHeight);
				if (profile != null) {
					profileLoaded = engine.setActiveMap(profile.offsets);
					if (!profileLoaded)
						Log.w("SpatialFPN", engine.getLastError());
				}
			} catch (IOException e) {
				engine.clearActiveMap();
				Log.w("SpatialFPN", "Ignoring invalid calibration profile", e);
			}
		}
		notifyState(State.IDLE, 0.0f, profileLoaded ?
				"Saved spatial calibration loaded." : "No saved spatial calibration.");
	}

	void detachCamera() {
		cancelInternal(State.FAILED, "Camera disconnected during autocalibration.");
		ThermalCameraHal oldCamera;
		synchronized (stateLock) {
			oldCamera = camera;
			camera = null;
			operationToken++;
			profileLoaded = false;
			engine.clearActiveMap();
		}
		if (oldCamera != null)
			oldCamera.invalidate();
	}

	boolean canHoldShutterClosed() {
		ThermalCameraHal current = camera;
		return current != null && current.canHoldShutterClosed();
	}

	boolean isCameraReady() {
		ThermalCameraHal current = camera;
		return current != null && current.isReady();
	}

	boolean hasLoadedProfile() { return profileLoaded; }

	State getState() { return state; }

	boolean isActive() {
		State current = state;
		return current == State.PREPARING || current == State.COLLECTING ||
				current == State.VALIDATING || current == State.COMMITTING;
	}

	void start() {
		final ThermalCameraHal current;
		final int token;
		synchronized (stateLock) {
			current = camera;
			if (isActive())
				return;
			if (current == null || !current.isReady()) {
				transitionLocked(State.FAILED, 0.0f, "Thermal camera is not ready.");
				return;
			}
			token = ++operationToken;
			state = State.PREPARING;
			shutterHeld = false;
		}
		notifyState(State.PREPARING, 0.0f, "Preparing spatial calibration…");
		worker.execute(() -> prepare(current, token));
	}

	private void prepare(ThermalCameraHal current, int token) {
		try {
			boolean held = false;
			if (current.canHoldShutterClosed()) {
				held = current.holdShutterClosed();
				if (!held)
					throw new IOException("Unable to close and hold the camera shutter.");
			}
			synchronized (stateLock) {
				if (operationToken != token || state != State.PREPARING) {
					if (held) current.restoreShutter();
					return;
				}
				shutterHeld = held;
				engine.begin();
				startedNs = System.nanoTime();
				lastFrameNs = startedNs;
				lastStatusNs = 0;
				state = State.COLLECTING;
			}
			notifyState(State.COLLECTING, 0.0f,
					"Stabilizing camera (2:00 remaining)…");
		} catch (Exception e) {
			fail(token, messageOf(e));
		}
	}

	/** Called on the camera frame thread. Correction is applied in-place for every consumer. */
	boolean processFrame(float[] temperatures) {
		State current = state;
		long now = System.nanoTime();
		boolean collect = false;
		int token = operationToken;
		if (current == State.COLLECTING) {
			lastFrameNs = now;
			collect = now - startedNs >= STABILIZATION_DURATION_NS;
		}
		try {
			if (!engine.processFrame(temperatures, collect)) {
				fail(token, engine.getLastError());
				return false;
			}
		} catch (RuntimeException exception) {
			fail(token, messageOf(exception));
			return false;
		}
		if (current != State.COLLECTING)
			return true;

		long elapsed = now - startedNs;
		if (elapsed >= TOTAL_DURATION_NS) {
			boolean validate = false;
			synchronized (stateLock) {
				if (state == State.COLLECTING && operationToken == token) {
					state = State.VALIDATING;
					validate = true;
				}
			}
			if (validate) {
				notifyState(State.VALIDATING, 1.0f, "Validating offset map…");
				worker.execute(() -> validateAndCommit(token));
			}
		} else if (now - lastStatusNs >= STATUS_INTERVAL_NS) {
			lastStatusNs = now;
			float progress = Math.max(0.0f, Math.min(1.0f,
					elapsed / (float) TOTAL_DURATION_NS));
			String detail;
			if (elapsed < STABILIZATION_DURATION_NS) {
				long seconds = TimeUnit.NANOSECONDS.toSeconds(
						STABILIZATION_DURATION_NS - elapsed + TimeUnit.SECONDS.toNanos(1) - 1);
				detail = String.format(Locale.ROOT,
						"Stabilizing camera (%d:%02d remaining)…", seconds / 60, seconds % 60);
			} else {
				long seconds = TimeUnit.NANOSECONDS.toSeconds(
						TOTAL_DURATION_NS - elapsed + TimeUnit.SECONDS.toNanos(1) - 1);
				detail = String.format(Locale.ROOT,
						"Collecting spatial offsets (%d:%02d remaining)…",
						seconds / 60, seconds % 60);
			}
			notifyState(State.COLLECTING, progress, detail);
		}
		return true;
	}

	private void validateAndCommit(int token) {
		try {
			SpatialCalibrationEngine.Candidate candidate = engine.finishCandidate();
			if (candidate == null)
				throw new IOException(engine.getLastError());
			synchronized (commitLock) {
				ThermalCameraHal current;
				synchronized (stateLock) {
					if (operationToken != token || state != State.VALIDATING)
						return;
					current = camera;
					if (current == null || !current.isReady())
						throw new IOException("Camera disconnected before calibration commit.");
					state = State.COMMITTING;
				}
				notifyState(State.COMMITTING, 1.0f, "Saving spatial calibration…");
				/* Restoring hardware is part of the transaction.  Do it before replacing
				 * the durable profile so an OPEN failure cannot publish a candidate while
				 * leaving the previous known-good calibration overwritten. */
				if (!restoreShutter())
					throw new IOException("Unable to restore the camera shutter.");
				store.commit(current.getPhysicalDeviceId(), width, height, candidate.offsets);
				if (!engine.setActiveMap(candidate.offsets))
					throw new IOException(engine.getLastError());
				profileLoaded = true;
				synchronized (stateLock) {
					if (operationToken != token)
						return;
					state = State.COMPLETED;
				}
				Log.i("SpatialFPN", String.format(Locale.ROOT,
						"Committed %d-frame map: invalid=%.4f rms=%.4fC native avg=%.1fus max=%.1fus",
						candidate.collectedFrames, candidate.invalidPixelFraction,
						candidate.spatialRms, candidate.averageProcessUs,
						candidate.maxProcessUs));
				notifyState(State.COMPLETED, 1.0f,
						"Spatial autocalibration completed and saved.");
			}
		} catch (Exception e) {
			fail(token, messageOf(e));
		}
	}

	void cancel() {
		cancelInternal(State.CANCELLED, "Spatial autocalibration cancelled.");
	}

	private void checkFrameTimeout() {
		if (state == State.COLLECTING && System.nanoTime() - lastFrameNs > FRAME_TIMEOUT_NS)
			fail(operationToken, "Timed out waiting for a thermal frame.");
	}

	private void fail(int token, String detail) {
		synchronized (stateLock) {
			if (operationToken != token || !isActive())
				return;
			operationToken++;
			state = State.FAILED;
			engine.cancel();
		}
		restoreShutter();
		notifyState(State.FAILED, 0.0f,
				detail == null || detail.isEmpty() ? "Spatial autocalibration failed." : detail);
	}

	private void cancelInternal(State terminal, String detail) {
		synchronized (commitLock) {
			synchronized (stateLock) {
				if (!isActive())
					return;
				operationToken++;
				state = terminal;
				engine.cancel();
			}
			restoreShutter();
			notifyState(terminal, 0.0f, detail);
		}
	}

	private boolean restoreShutter() {
		ThermalCameraHal current = camera;
		if (shutterHeld && current != null) {
			try {
				if (!current.restoreShutter()) {
					Log.w("SpatialFPN", "Camera rejected the shutter restore command");
					return false;
				}
				shutterHeld = false;
			} catch (RuntimeException e) {
				Log.w("SpatialFPN", "Unable to restore shutter", e);
				return false;
			}
		}
		return true;
	}

	private void transitionLocked(State target, float progress, String detail) {
		state = target;
		notifyState(target, progress, detail);
	}

	private void notifyState(State newState, float progress, String detail) {
		if (listener != null)
			listener.onCalibrationState(newState, progress, detail);
	}

	private static String messageOf(Throwable exception) {
		String message = exception.getMessage();
		return message == null || message.isEmpty() ?
				"Spatial autocalibration failed." : message;
	}

	@Override public void close() {
		cancel();
		watchdog.shutdownNow();
		worker.shutdownNow();
		engine.close();
	}
}
