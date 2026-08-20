package be.ntmn.inficam;

import static java.lang.Float.NaN;
import static java.lang.Float.isInfinite;
import static java.lang.Float.isNaN;
import static java.lang.Math.ceil;
import static java.lang.Math.floor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.res.Configuration;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.EditText;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import be.ntmn.libinficam.InfiCam;

public class MainActivity extends BaseActivity {
	/* These are public for Settings things to access them. */
	public final InfiCam infiCam = new InfiCam();
	public ThermalRenderer thermalRenderer;

	private SurfaceMuxer surfaceMuxer;
	private SurfaceMuxer.InputSurface inputSurface; /* Input surface for the thermal image. */
	private SurfaceMuxer.ThroughSurface thruSurface; /* We sharpen separately to do it lo-res. */
	private SurfaceMuxer.InputSurface videoSurface; /* To draw video from the normal camera. */
	private Overlay overlayScreen, overlayRecord, overlayPicture, overlayWeb;
	private SurfaceMuxer.OutputSurface outScreen, outRecord, outWeb;
	private final Overlay.Data overlayData = new Overlay.Data();
	private final Overlay.Data renderOverlayData = new Overlay.Data();
	private int iMode;

	private volatile UsbDevice usb_device;
	private volatile UsbDeviceConnection usbConnection;
	public final Object frameLock = new Object();
	private int picWidth = 1024, picHeight = 768;
	private int vidWidth = 1024, vidHeight = 768;
	private boolean takePic = false;
	private volatile boolean sharePic = false;
	private volatile boolean disconnecting = false;
	private final Object usbLifecycleLock = new Object();
	private boolean usbConnectionPending = false;
	private boolean activityStarted = false;
	private final SurfaceRecorder recorder = new SurfaceRecorder();
	private final SurfaceRecorder chartRecorder = new SurfaceRecorder();
	private SurfaceMuxer.OutputSurface outChartRecord;
	private boolean recordAudio;
	private final Rect rect = new Rect(); /* To use during frames, to avoid allocating it there. */

	private CameraView cameraView;
	private MessageView messageView;
	private ViewGroup dialogBackground;
	private Settings activeSettingsDialog;
	private SettingsMain settings;
	private SettingsTherm settingsTherm;
	private SettingsMeasure settingsMeasure;
	private SettingsPalette settingsPalette;
	private LinearLayout buttonsLeft, buttonsRight;
	private ConstraintLayout.LayoutParams buttonsLeftLayout, buttonsRightLayout;
	private SliderDouble rangeSlider;
	private FrameLayout cameraContainer;
	private ImageButton buttonPhoto, buttonShare, buttonWebView, buttonTimeChart;
	private TimeChartView timeChart;
	private int timeChartState = 0; // 0 hidden, 1 recording, 2 stopped/visible
	private static final int TIME_CHART_HEIGHT_DP = 300;
	private static final int TIME_CHART_BOTTOM_GAP_DP = 9;
	private static final int TIME_CHART_BUTTON_RESERVE_DP = 72;
	private TextView webViewAddress;
	private WebViewServer webViewServer;
	private boolean webMirror;
	private long lastWebCaptureNs;
	private static final long WEB_FRAME_INTERVAL_NS = 40000000L; // target camera rate: 25 FPS
	private boolean rotate = false;
	private int orientation = 0;
	private boolean swapControls = false;
	private volatile boolean applyLocalCorrection = true;
	private volatile float localCorrection = 0.0f;
	private volatile int connectGeneration = 0;
	private volatile boolean suppressCalibrationRequest = false;
	private volatile boolean acceptCameraSettings = false;
	private boolean pendingCalibrationAfterThermDialog = false;
	private boolean calibrationUiActive = false;
	private volatile boolean overTempLockoutActive = false;
	private int calibrationMessageStep = 0;
	private float scale = 1.0f;
	private int imgType;
	private int imgQuality;
	private float[] latestTempBuffer = new float[0];
	private float[] renderTempBuffer = new float[0];
	private final InfiCam.FrameInfo latestFrameInfo = new InfiCam.FrameInfo();
	private Overlay.MinMaxAvgCet latestMmac = null;
	private float latestSensorMax = NaN;
	private long latestFrameSequence = 0;
	private boolean renderPending = false;
	private volatile boolean renderingEnabled = false;
	private static final boolean RENDER_TIMING_LOGS = true;
	private long renderStatsStartNs = 0;
	private long incomingFrameCount = 0;
	private long renderedFrameCount = 0;
	private long coalescedFrameCount = 0;
	private long renderTotalNs = 0;
	private long renderPaletteTotalNs = 0;
	private long renderLockCanvasTotalNs = 0;
	private long renderUnlockCanvasTotalNs = 0;
	private long renderInputDrawTotalNs = 0;
	private long renderThruSwapTotalNs = 0;
	private long renderScreenSwapTotalNs = 0;
	private long renderRecordSwapTotalNs = 0;
	private long renderScreenSwapCount = 0;
	private long renderRecordSwapCount = 0;

	private final Runnable calibrationMessageRunnable = new Runnable() {
		@Override
		public void run() {
			if (!calibrationUiActive)
				return;
			calibrationMessageStep = (calibrationMessageStep + 1) % 3;
			String dots = calibrationMessageStep == 0 ? "." :
					calibrationMessageStep == 1 ? ".." : "...";
			messageView.setMessage(getString(R.string.msg_calibrating) + dots);
			handler.postDelayed(this, 400);
		}
	};

	private Bitmap imgCompressBitmap;
	private Bitmap imgCompressChartBitmap;
	private float chartSampleRateSeconds = 0.1f;
	private boolean exportChartSeparately;
	private float paletteManualMin = 0.0f;
	private float paletteManualMax = 100.0f;

	private class ImgCompressThread extends Thread {
		private volatile boolean stop = false;
		public final ReentrantLock lock = new ReentrantLock();
		public final Condition cond = lock.newCondition();

		@Override
		public void run() {
			lock.lock();
			while (true) {
				try {
					cond.await();
				} catch (Exception e) {
					e.printStackTrace();
					continue;
				}
				if (stop)
					break;
				try {
					Util.writeImage(getApplicationContext(), imgCompressBitmap, imgType,
							imgQuality);
					imgCompressBitmap.recycle();
					if (imgCompressChartBitmap != null) {
						Util.writeImage(getApplicationContext(), imgCompressChartBitmap, imgType, imgQuality);
						imgCompressChartBitmap.recycle();
						imgCompressChartBitmap = null;
					}
				} catch (Exception e) {
					handler.post(() -> messageView.showMessage(e.getMessage()));
				}
				handler.postDelayed(() -> {
					buttonPhoto.setEnabled(true);
					buttonPhoto.setColorFilter(null);
				},200); //keep button visibly activated for a short time, or you can't see it.
			}
			lock.unlock();
		}

		public void shutdown() {
			lock.lock();
			stop = true;
			cond.signal();
			lock.unlock();
			try {
				join();
			} catch (Exception e) { e.printStackTrace(); }
		}
	}
	private ImgCompressThread imgCompressThread;


	private final USBMonitor usbMonitor = new USBMonitor() {
		@Override
		public void onDeviceFound(UsbDevice p_usb_device) {
			if (!activityStarted || usbConnection != null || usbConnectionPending)
				return;
			if(p_usb_device.getProductName() == null){
				return;
			}
			/* This P2 Pro reports the generic product name "USB Camera", so VID:PID
			 * is the only reliable identifier available to Android. */
			boolean isP2Pro = p_usb_device.getVendorId() == 0x0bda &&
					p_usb_device.getProductId() == 0x5830;
			//From original app, yes it's big.
			boolean is_ours =
				isP2Pro || (!p_usb_device.getProductName().contains("Search") &&
				(p_usb_device.getProductName().contains("FX3") ||
					p_usb_device.getProductName().contains("PNS") ||
					p_usb_device.getProductName().contains("T5") ||
					p_usb_device.getProductName().contains("T2_V2") ||
					p_usb_device.getProductName().contains("T2S+") ||
					p_usb_device.getProductName().contains("T2-Mg_V2") ||
					p_usb_device.getProductName().contains("Xtherm") ||
					p_usb_device.getProductName().contains("Xmodule") ||
					p_usb_device.getProductName().contains("S0") ||
					p_usb_device.getProductName().contains("S1") ||
					p_usb_device.getProductName().contains("T2L") ||
					p_usb_device.getProductName().contains("T2S") ||
					p_usb_device.getProductName().contains("DL") ||
					p_usb_device.getProductName().contains("DV") ||
					p_usb_device.getProductName().contains("T3S") ||
					p_usb_device.getProductName().contains("T3H") ||
					p_usb_device.getProductName().contains("T3-612") ||
					p_usb_device.getProductName().contains("T3Pro") ||
					p_usb_device.getProductName().contains("T3C") ||
					p_usb_device.getProductName().contains("DP") ||
					p_usb_device.getProductName().contains("T19") ||
					p_usb_device.getProductName().contains("DX300")));
			if (!is_ours) {
				Log.e("inficam","Device is not recognized: "+p_usb_device.getProductName());
				return;
			}

			usb_device = p_usb_device;
			usbConnectionPending = true;
			final UsbDevice foundDevice = p_usb_device;

			/* Connecting to a UVC device needs camera permission. */
			askPermission(Manifest.permission.CAMERA, granted -> {
				if (!granted) {
					usbConnectionPending = false;
					messageView.showMessage(R.string.msg_permdenied_cam);
					return;
				}
				connect(foundDevice, new ConnectCallback() {
						@Override
						public void onConnected(UsbDevice dev, UsbDeviceConnection conn) {
							disconnect(); /* Important! Frame callback not allowed during connect. */
							usbConnectionPending = false;
							usb_device = dev;
							usbConnection = conn;
							disconnecting = false;
							int token = ++connectGeneration;
							setCalibrationUi(true);
							startCameraConnectThread(dev, conn, token);
						}

					@Override
						public void onPermissionDenied(UsbDevice dev) {
							usbConnectionPending = false;
							messageView.showMessage(R.string.msg_permdenied_usb);
						}

					@Override
						public void onFailed(UsbDevice dev) {
							usbConnectionPending = false;
							messageView.showMessage(getString(R.string.msg_connect_failed));
							scheduleReconnect(700);
					}
				});
			});
		}

		@Override
		public void onDisconnect(UsbDevice dev) {
			if (dev != null && dev.equals(usb_device)) {
				usbConnectionPending = false;
				disconnect();
				scheduleReconnect(400);
			}
		}
	};
	private final Runnable reconnectRunnable = () -> {
		if (activityStarted && usbConnection == null && !usbConnectionPending) {
			usb_device = null;
			usbMonitor.scan();
		}
	};

	private final SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback() {
		@Override
		public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
			outScreen =
					new SurfaceMuxer.OutputSurface(surfaceMuxer, surfaceHolder.getSurface());
		}

		@Override
		public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int w, int h) {
			if (outScreen != null)
				outScreen.setSize(w, h);
			overlayScreen.setSize(w, h);
		}

			@Override
			public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
				if (outScreen != null) {
					outScreen.release();
					outScreen = null;
				}
			}
		};

	/* If the orientation changes between 0 and 180 or 90 and 270 suddenly, onDisplayChanged()
	 *	 is called, but not onConfigurationChanged().
	 */
	private final DisplayManager.DisplayListener displayListener =
		new DisplayManager.DisplayListener() {
			@Override
			public void onDisplayAdded(int displayId) { /* Empty. */ }

		@Override
		public void onDisplayChanged(int displayId) { updateOrientation(); }

		@Override
		public void onDisplayRemoved(int displayId) { /* Empty. */ }
	};

	private final BroadcastReceiver batteryRecevier = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) { updateBatLevel(intent); }
	};

	/* This is called by infiCam to run every frame, it calls the thermal renderer which writes
	 *   the surface, it's good to do the work like applying palette and doing
	 *   complicated measurements here to avoid blocking the main thread. Once this is done we fill
	 *   overlayData with the info needed to draw the overlays and then post a single coalesced UI
	 *   render job to do the work that should happen there (everything involving the EGL context
	 *   we've created there).
	 */
	private final InfiCam.FrameCallback frameCallback = new InfiCam.FrameCallback() {
			@Override
			public void onFrame(InfiCam.FrameInfo fi, float[] temp) {
				/* Note this is called from another thread. */
				if (!renderingEnabled)
					return;
				synchronized (frameLock) {
					if (!renderingEnabled)
						return;
					if (renderStatsStartNs == 0)
						renderStatsStartNs = System.nanoTime();
					if (latestTempBuffer.length != temp.length)
						latestTempBuffer = new float[temp.length];
					System.arraycopy(temp, 0, latestTempBuffer, 0, temp.length);
					applyLocalCorrection(latestTempBuffer);
					copyFrameInfo(fi, latestFrameInfo);
					overlayData.fi = latestFrameInfo;
					overlayData.temp = latestTempBuffer;
					incomingFrameCount++;
					latestFrameSequence++;

					if (scale > 1.0f) {
						float lost = (1.0f - 1.0f / scale) / 2.0f;
						latestMmac = Overlay.computeMmacRect(
							latestTempBuffer,
							(int) (lost * fi.width),
							(int) (lost * fi.height),
							(int) ((1.0f - lost) * fi.width) + 1,
							(int) ((1.0f - lost) * fi.height) + 1,
							fi.width
						);
					} else {
						latestMmac = Overlay.computeMmac(latestTempBuffer, fi.width, fi.height);
					}
					overlayData.mmac = latestMmac;
					latestSensorMax = getCorrectedMaxTempClipping(fi.settings.max_temp_clipping);

					if (acceptCameraSettings && !overTempLockoutActive &&
							!infiCam.isCalibrating() &&
							!isNaN(latestMmac.max) && latestMmac.max > settingsTherm.getRange()[1] && //over max of the range
							settings.overtempEnabled){ //setting enabled
						Log.e("inficam", "Over temperature protection triggered at "+ latestMmac.max + "C");
						handler.post(() -> overTempLockout());
					}

					if(inputSurface.surface == null) { return; } //We exited the app

					if (renderPending) {
						coalescedFrameCount++;
					} else {
						renderPending = true;
						handler.post(renderFrameRunnable);
					}
				}
			}
		};

	private final Runnable renderFrameRunnable = new Runnable() {
		@Override
		public void run() {
			long renderStartNs = System.nanoTime();
			long sequence;
			float sensorMax;
			synchronized (frameLock) {
				if (!renderingEnabled || latestMmac == null || inputSurface.surface == null) {
					renderPending = false;
					return;
				}
				if (renderTempBuffer.length != latestTempBuffer.length)
					renderTempBuffer = new float[latestTempBuffer.length];
				System.arraycopy(latestTempBuffer, 0, renderTempBuffer, 0,
						latestTempBuffer.length);
				copyFrameInfo(latestFrameInfo, renderOverlayData.fi);
				renderOverlayData.temp = renderTempBuffer;
				renderOverlayData.mmac = latestMmac;
				renderOverlayData.rangeMin = overlayData.rangeMin;
				renderOverlayData.rangeMax = overlayData.rangeMax;
				renderOverlayData.rotate = overlayData.rotate;
				renderOverlayData.mirror = overlayData.mirror;
				renderOverlayData.rotate90 = overlayData.rotate90;
				renderOverlayData.showMin = overlayData.showMin;
				renderOverlayData.showMax = overlayData.showMax;
				renderOverlayData.showCenter = overlayData.showCenter;
				renderOverlayData.showPalette = overlayData.showPalette;
				renderOverlayData.scale = overlayData.scale;
				renderOverlayData.tempUnit = overlayData.tempUnit;
				sequence = latestFrameSequence;
				sensorMax = latestSensorMax;
			}

			float rangeMin = Float.isNaN(renderOverlayData.rangeMin) ?
					renderOverlayData.mmac.min : renderOverlayData.rangeMin;
			float rangeMax = Float.isNaN(renderOverlayData.rangeMax) ?
					renderOverlayData.mmac.max : renderOverlayData.rangeMax;
			thermalRenderer.renderTemperatures(inputSurface.surface,
					settingsPalette.paletteMap, renderTempBuffer, rangeMin, rangeMax, sensorMax);

			RenderTimings timings = handleFrame(renderOverlayData);
			timings.totalNs = System.nanoTime() - renderStartNs;
			logRenderTimings(sequence, timings);
		}
	};

	private void applyLocalCorrection(float[] temp) {
		if (!applyLocalCorrection || localCorrection == 0.0f)
			return;
		for (int i = 0; i < temp.length; ++i)
			temp[i] += localCorrection;
	}

	private static class RenderTimings {
		long totalNs;
		long inputDrawNs;
		long thruSwapNs;
		long screenSwapNs;
		long recordSwapNs;
	}

	private static double avgMs(long totalNs, long count) {
		if (count <= 0)
			return 0.0;
		return totalNs / 1000000.0 / count;
	}

	private void copyFrameInfo(InfiCam.FrameInfo src, InfiCam.FrameInfo dst) {
		dst.width = src.width;
		dst.height = src.height;
		dst.settings.range = src.settings.range;
		dst.settings.max_temp_clipping = src.settings.max_temp_clipping;
		dst.settings.correction = src.settings.correction;
		dst.settings.temp_reflected = src.settings.temp_reflected;
		dst.settings.temp_air = src.settings.temp_air;
		dst.settings.humidity = src.settings.humidity;
		dst.settings.emissivity = src.settings.emissivity;
		dst.settings.distance = src.settings.distance;
	}

	private void logRenderTimings(long renderedSequence, RenderTimings timings) {
		synchronized (frameLock) {
			renderedFrameCount++;
			renderTotalNs += timings.totalNs;
			renderPaletteTotalNs += thermalRenderer.lastPaletteNs;
			renderLockCanvasTotalNs += thermalRenderer.lastLockCanvasNs;
			renderUnlockCanvasTotalNs += thermalRenderer.lastUnlockCanvasNs;
			renderInputDrawTotalNs += timings.inputDrawNs;
			renderThruSwapTotalNs += timings.thruSwapNs;
			if (timings.screenSwapNs > 0) {
				renderScreenSwapTotalNs += timings.screenSwapNs;
				renderScreenSwapCount++;
			}
			if (timings.recordSwapNs > 0) {
				renderRecordSwapTotalNs += timings.recordSwapNs;
				renderRecordSwapCount++;
			}

			long nowNs = System.nanoTime();
			long elapsedNs = nowNs - renderStatsStartNs;
			if (RENDER_TIMING_LOGS && renderStatsStartNs != 0 &&
					elapsedNs >= 1000000000L) {
				double seconds = elapsedNs / 1000000000.0;
				Log.d("inficam", String.format(Locale.US,
						"Render stats: incoming=%.1ffps rendered=%.1ffps coalesced=%d " +
								"total=%.2fms palette=%.2fms lockCanvas=%.2fms unlockCanvas=%.2fms " +
								"inputDraw=%.2fms thruSwap=%.2fms screenSwap=%.2fms recordSwap=%.2fms",
						incomingFrameCount / seconds,
						renderedFrameCount / seconds,
						coalescedFrameCount,
						avgMs(renderTotalNs, renderedFrameCount),
						avgMs(renderPaletteTotalNs, renderedFrameCount),
						avgMs(renderLockCanvasTotalNs, renderedFrameCount),
						avgMs(renderUnlockCanvasTotalNs, renderedFrameCount),
						avgMs(renderInputDrawTotalNs, renderedFrameCount),
						avgMs(renderThruSwapTotalNs, renderedFrameCount),
						avgMs(renderScreenSwapTotalNs, renderScreenSwapCount),
						avgMs(renderRecordSwapTotalNs, renderRecordSwapCount)));
				renderStatsStartNs = nowNs;
				incomingFrameCount = 0;
				renderedFrameCount = 0;
				coalescedFrameCount = 0;
				renderTotalNs = 0;
				renderPaletteTotalNs = 0;
				renderLockCanvasTotalNs = 0;
				renderUnlockCanvasTotalNs = 0;
				renderInputDrawTotalNs = 0;
				renderThruSwapTotalNs = 0;
				renderScreenSwapTotalNs = 0;
				renderRecordSwapTotalNs = 0;
				renderScreenSwapCount = 0;
				renderRecordSwapCount = 0;
			}

			if (latestFrameSequence != renderedSequence && inputSurface.surface != null) {
				handler.post(renderFrameRunnable);
			} else {
				renderPending = false;
			}
		}
	}

	private void resetRenderStats() {
		synchronized (frameLock) {
			renderStatsStartNs = 0;
			incomingFrameCount = 0;
			renderedFrameCount = 0;
			coalescedFrameCount = 0;
			renderTotalNs = 0;
			renderPaletteTotalNs = 0;
			renderLockCanvasTotalNs = 0;
			renderUnlockCanvasTotalNs = 0;
			renderInputDrawTotalNs = 0;
			renderThruSwapTotalNs = 0;
			renderScreenSwapTotalNs = 0;
			renderRecordSwapTotalNs = 0;
			renderScreenSwapCount = 0;
			renderRecordSwapCount = 0;
		}
	}

	private float getCorrectedMaxTempClipping(float maxTempClipping) {
		if (!applyLocalCorrection)
			return maxTempClipping;
		return maxTempClipping + localCorrection;
	}

	private final InfiCam.SettingsCallback settingsCallback =
		new InfiCam.SettingsCallback() {
			@Override
			/* Note this is called from another thread. */
			public void onSettings(InfiCam.CamSettings camSettings) {
				if(settingsTherm == null || !acceptCameraSettings){
					return;
				}
				final int token = connectGeneration;
				final float emissivity = camSettings.emissivity;
				final float tempReflected = camSettings.temp_reflected;
				final float tempAir = camSettings.temp_air;
				final float humidity = camSettings.humidity;
				final int distance = camSettings.distance;
				final float correction = camSettings.correction;
				final int range = camSettings.range;
				handler.post(() -> {
					if(token != connectGeneration || disconnecting || !acceptCameraSettings)
						return;
					settingsTherm.setSettings(
						emissivity, tempReflected, tempAir, humidity,
						distance, correction, range);
				});
			}
		};

	private boolean isCurrentConnection(int token, UsbDeviceConnection conn) {
		return connectGeneration == token && usbConnection == conn && !disconnecting;
	}

	private void runOnUiThreadSync(Runnable runnable) throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(1);
		RuntimeException[] exception = new RuntimeException[1];
		handler.post(() -> {
			try {
				runnable.run();
			} catch (RuntimeException e) {
				exception[0] = e;
			} finally {
				latch.countDown();
			}
		});
		latch.await();
		if (exception[0] != null)
			throw exception[0];
	}

	private void startCameraConnectThread(UsbDevice dev, UsbDeviceConnection conn, int token) {
		new Thread(() -> {
			try {
				synchronized (usbLifecycleLock) {
					if (!isCurrentConnection(token, conn))
						return;
					infiCam.connect(conn.getFileDescriptor());
				}
				int width = infiCam.getWidth();
				int height = infiCam.getHeight();
				float[][] ranges = infiCam.getRanges();

				runOnUiThreadSync(() -> {
					if (!isCurrentConnection(token, conn))
						return;
					/* Size is only important for cubic interpolation. */
					inputSurface.setSize(width, height);
					thruSurface.setSize(width, height);
					settingsTherm.init(MainActivity.this, ranges);
					thermalRenderer = new ThermalRenderer(width, height);
				});
				if (!isCurrentConnection(token, conn))
					return;

				synchronized (usbLifecycleLock) {
					if (!isCurrentConnection(token, conn))
						return;
					infiCam.startStream();
				}
				if (!isCurrentConnection(token, conn))
					return;
				/* Start accepting frames immediately. P2 Pro has no calibration phase;
				 * delaying this until settings synchronisation finishes can otherwise
				 * leave an already-running UVC stream invisible. */
				synchronized (usbLifecycleLock) {
					if (!isCurrentConnection(token, conn))
						return;
					infiCam.setFrameCallback(frameCallback);
				}

				suppressCalibrationRequest = true;
				try {
					runOnUiThreadSync(() -> {
						if (isCurrentConnection(token, conn))
							settingsTherm.load(); //needs stream to communicate with the camera
					});
				} finally {
					suppressCalibrationRequest = false;
				}
				if (!isCurrentConnection(token, conn))
					return;

				/* P2 Pro's 256x384 UVC stream already contains calibrated Y16
				 * temperatures. It does not implement this app's zoom-based shutter
				 * calibration protocol, so waiting for it leaves the UI on
				 * "Calibrating..." forever. */
				boolean isP2Pro = dev.getVendorId() == 0x0bda &&
						dev.getProductId() == 0x5830;
				if (!isP2Pro)
					infiCam.calibrateBlocking();
				handler.post(() -> {
					if (!isCurrentConnection(token, conn))
						return;
					acceptCameraSettings = true;
					setCalibrationUi(false);
					messageView.clearMessage();
					messageView.showMessage(getString(R.string.msg_connected,
							dev.getProductName()));
					resetRenderStats();
					Log.i("inficam", String.format(Locale.US,
							"Frame diagnostics started: connection=%d size=%dx%d",
							token, width, height));
				});
			} catch (Exception e) {
				String message = e.getMessage() == null ? getString(R.string.msg_connect_failed) :
						e.getMessage();
				handler.post(() -> {
					if (!isCurrentConnection(token, conn))
						return;
					disconnect();
					messageView.showMessage(message);
				});
			}
		}, "InfiCam connect").start();
	}

	private void setViewTreeEnabled(View view, boolean enabled) {
		if (view == null)
			return;
		view.setEnabled(enabled);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); ++i)
				setViewTreeEnabled(group.getChildAt(i), enabled);
		}
	}

	private void setCalibrationUi(boolean active) {
		if (calibrationUiActive == active)
			return;
		calibrationUiActive = active;
		handler.removeCallbacks(calibrationMessageRunnable);

		setViewTreeEnabled(buttonsLeft, !active);
		setViewTreeEnabled(buttonsRight, !active);
		setViewTreeEnabled(rangeSlider, !active);
		setViewTreeEnabled(dialogBackground, !active);
		if (cameraView != null)
			cameraView.setEnabled(!active);
		if (dialogBackground != null && active)
			hideSettingsDialog();
		if (buttonsLeft != null)
			buttonsLeft.setAlpha(active ? 0.35f : 1.0f);
		if (buttonsRight != null)
			buttonsRight.setAlpha(active ? 0.35f : 1.0f);
		if (rangeSlider != null)
			rangeSlider.setAlpha(active ? 0.35f : 1.0f);

		if (active) {
			calibrationMessageStep = 2;
			calibrationMessageRunnable.run();
		} else if (messageView != null) {
			messageView.clearMessage();
		}
	}

	private void waitForCalibrationDone() {
		new Thread(() -> {
			try {
				while (infiCam.isCalibrating())
					Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			handler.post(() -> {
				if (!infiCam.isCalibrating())
					setCalibrationUi(false);
			});
		}, "InfiCam calibration wait").start();
	}

	private void getRect(Rect r, int w, int h) { /* Git rekt! */
		int sw = w, sh = h, iw = infiCam.getWidth(), ih = infiCam.getHeight();
		if (w == 0 || ih == 0) { iw = 4; ih = 3; }
		if (orientation == Surface.ROTATION_0 || orientation == Surface.ROTATION_180) {
			ih ^= iw;
			iw ^= ih;
			ih ^= iw;
		}
		if (ih * w / iw > h)
			sw = iw * h / ih;
		else sh = ih * w / iw;
		r.set(w / 2 - sw / 2, h / 2 - sh / 2,
				w / 2 - sw / 2 + sw, h / 2 - sh / 2 + sh);
	}


	private long drawFrame(SurfaceMuxer.OutputSurface os, Overlay overlay, boolean swap,
						   Overlay.Data data) {
		return drawFrame(os, overlay, swap, data, false);
	}

	private long drawFrame(SurfaceMuxer.OutputSurface os, Overlay overlay, boolean swap,
						   Overlay.Data data, boolean includeChart) {
		getRect(rect, os.width, os.height);
		os.clear(0, 0, 0, 1);
		thruSurface.draw(
			os,
			iMode,
			rect.left,
			rect.top,
			rect.width(),
			rect.height()
		);
		overlay.draw(data, settingsPalette, rect);
		overlay.surface.draw(os, SurfaceMuxer.DM_LINEAR);
		if (includeChart && timeChartState != 0 && timeChart != null) {
			Bitmap chart = timeChart.snapshot();
			if (chart != null) {
				boolean landscape = orientation == Surface.ROTATION_90 ||
						orientation == Surface.ROTATION_270;
				int cw = landscape ? os.width * 38 / 100 : os.width;
				int ch = landscape ? os.height : chart.getHeight() * os.width / chart.getWidth();
				os.drawBitmap(chart, landscape ? os.width - cw : 0,
						landscape ? 0 : os.height - ch, cw, ch);
				chart.recycle();
			}
		}
		// TODO draw normal video if needed
		if (swap) {
			os.setPresentationTime(inputSurface.surfaceTexture.getTimestamp());
			long startNs = System.nanoTime();
			os.swapBuffers();
			return System.nanoTime() - startNs;
		}
		return 0;
	}

	private RenderTimings handleFrame(Overlay.Data data) {
		RenderTimings timings = new RenderTimings();
		if (disconnecting) {
			/* Don't try stuff when disconnected. */
			return timings;
		}
		if (timeChart != null && timeChart.isRecording() && data.mmac != null)
			timeChart.sample(data.mmac.max, data.mmac.min, data.mmac.center, data.tempUnit,
					data.showMax, data.showMin, data.showCenter);

		/* At this point we are certain the frame and the overlayData are matched up with
		 *   each-other, so now we can do stuff like taking a picture, "the frame" here
		 *   meaning what's in the SurfaceTexture buffers after the updateTexImage() calls
		 *   surfaceMuxer should do.
		 */
		long startNs = System.nanoTime();
		inputSurface.draw(thruSurface, SurfaceMuxer.DM_SHARPEN);
		timings.inputDrawNs = System.nanoTime() - startNs;
		startNs = System.nanoTime();
		thruSurface.swapBuffers();
		timings.thruSwapNs = System.nanoTime() - startNs;

		if (takePic && imgCompressThread == null) {
			messageView.showMessage(R.string.msg_permdenied_storage);
		} else if (takePic && imgCompressThread.lock.tryLock()) {
			imgCompressChartBitmap = null;
			int w = picWidth, h = picHeight;
			if (orientation == Surface.ROTATION_0 || orientation == Surface.ROTATION_180) {
				h ^= w;
				w ^= h;
				h ^= w;
			}
			SurfaceMuxer.OutputSurface outPicture =
					new SurfaceMuxer.OutputSurface(surfaceMuxer, null, w, h);
			overlayPicture.setSize(w, h);
			drawFrame(outPicture, overlayPicture, false, data);
			Bitmap picture = outPicture.getBitmap();
			outPicture.release();
			if (exportChartSeparately && timeChartState != 0 && timeChart != null) {
				imgCompressChartBitmap = timeChart.snapshot();
				imgCompressBitmap = picture;
			} else {
				imgCompressBitmap = addTimeChart(picture);
			}
			imgCompressThread.cond.signal();
			imgCompressThread.lock.unlock();
			takePic = false;
			messageView.shortMessage(R.string.msg_captured);
			buttonPhoto.setEnabled(false);
			buttonPhoto.setColorFilter(Color.RED);
		}

		if (sharePic && buttonShare != null) {
			try {
				Bitmap shareBitmap = renderCapture(data);
				sharePic = false;
				buttonShare.setEnabled(true);
				buttonShare.setColorFilter(null);
				Util.shareImage(this, shareBitmap, msg -> messageView.showMessage(msg));
			} catch (RuntimeException e) {
				sharePic = false;
				buttonShare.setEnabled(true);
				buttonShare.setColorFilter(null);
				messageView.showMessage(R.string.msg_share_failed);
				Log.w("inficam", "Unable to render image for sharing", e);
			}
		}

		if (webViewServer != null && webViewServer.wantsFrame() && outWeb != null &&
				System.nanoTime() - lastWebCaptureNs >= WEB_FRAME_INTERVAL_NS) {
			lastWebCaptureNs = System.nanoTime();
			Bitmap webBitmap = null;
			try {
				overlayWeb.setSize(outWeb.width, outWeb.height);
				drawFrame(outWeb, overlayWeb, false, data);
				webBitmap = outWeb.getBitmap();
				if (webViewServer.publish(webBitmap))
					webBitmap = null; // The asynchronous encoder owns it now.
			} catch (RuntimeException e) {
				Log.w("inficam", "Web View frame failed", e);
			} finally {
				if (webBitmap != null)
					webBitmap.recycle();
			}
		}

		if (outScreen != null)
			timings.screenSwapNs = drawFrame(outScreen, overlayScreen, true, data);
		if (outRecord != null)
			timings.recordSwapNs = drawFrame(outRecord, overlayRecord, true, data,
				!exportChartSeparately);
		if (outChartRecord != null && timeChart != null) {
			Bitmap chart = timeChart.snapshot();
			if (chart != null) {
				drawChartVideoFrame(outChartRecord, chart);
				chart.recycle();
				outChartRecord.setPresentationTime(inputSurface.surfaceTexture.getTimestamp());
				outChartRecord.swapBuffers();
			}
		}

		return timings;
	}

	/** Preserve the chart aspect ratio if the view changes size while recording. */
	private void drawChartVideoFrame(SurfaceMuxer.OutputSurface output, Bitmap chart) {
		output.clear(1, 1, 1, 1);
		int sourceWidth = chart.getWidth();
		int sourceHeight = chart.getHeight();
		if (sourceWidth <= 0 || sourceHeight <= 0 || output.width <= 0 || output.height <= 0)
			return;
		float scale = Math.min(output.width / (float) sourceWidth,
				output.height / (float) sourceHeight);
		int width = Math.min(output.width, Math.max(1, Math.round(sourceWidth * scale)));
		int height = Math.min(output.height, Math.max(1, Math.round(sourceHeight * scale)));
		output.drawBitmap(chart, (output.width - width) / 2, (output.height - height) / 2,
				width, height);
	}

	private Bitmap renderCapture(Overlay.Data data) {
		int w = picWidth, h = picHeight;
		if (orientation == Surface.ROTATION_0 || orientation == Surface.ROTATION_180) {
			h ^= w;
			w ^= h;
			h ^= w;
		}
		SurfaceMuxer.OutputSurface outPicture =
				new SurfaceMuxer.OutputSurface(surfaceMuxer, null, w, h);
		overlayPicture.setSize(w, h);
		drawFrame(outPicture, overlayPicture, false, data);
		Bitmap bitmap = outPicture.getBitmap();
		outPicture.release();
		return addTimeChart(bitmap);
	}

	private Bitmap addTimeChart(Bitmap bitmap) {
		if (bitmap == null || timeChartState == 0 || timeChart == null)
			return bitmap;
		Bitmap chart = timeChart.snapshot();
		if (chart == null)
			return bitmap;
		boolean landscape = orientation == Surface.ROTATION_90 ||
				orientation == Surface.ROTATION_270;
		int chartWidth = landscape ? bitmap.getWidth() * 38 / 100 : bitmap.getWidth();
		int chartHeight = landscape ? bitmap.getHeight() :
				chart.getHeight() * bitmap.getWidth() / chart.getWidth();
		/* Exported composition is contiguous, matching the chart/video layout. */
		int gap = 0;
		Bitmap combined = Bitmap.createBitmap(
				landscape ? bitmap.getWidth() + chartWidth : bitmap.getWidth(),
				landscape ? bitmap.getHeight() : bitmap.getHeight() + gap + chartHeight,
				Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(combined);
		canvas.drawBitmap(bitmap, 0, 0, null);
		Bitmap scaledChart = Bitmap.createScaledBitmap(chart, chartWidth, chartHeight, true);
		canvas.drawBitmap(scaledChart, landscape ? bitmap.getWidth() : 0,
				landscape ? 0 : bitmap.getHeight() + gap, null);
		if (scaledChart != chart)
			scaledChart.recycle();
		chart.recycle();
		bitmap.recycle();
		return combined;
	}

	private void toggleWebView() {
		if (webViewServer == null)
			return;
		if (webViewServer.isRunning()) {
			webViewServer.stop();
			webViewAddress.setVisibility(View.GONE);
			buttonWebView.setColorFilter(null);
			messageView.showMessage(R.string.msg_web_stopped);
			return;
		}
		try {
			webViewServer.setSnapshotFormat(imgType, imgQuality);
			String url = webViewServer.start();
			webViewAddress.setText(url);
			webViewAddress.setVisibility(View.VISIBLE);
			buttonWebView.setColorFilter(Color.RED);
			messageView.showMessage(getString(R.string.msg_web_started, url));
		} catch (IOException e) {
			messageView.showMessage(R.string.msg_web_failed);
			Log.w("inficam", "Unable to start Web View", e);
		}
	}

	private void handleWebCommand(String command, String value) {
		handler.post(() -> {
			if ("palette".equals(command)) {
				int next = (settingsPalette.getPalette().get() + 1) % Palette.palettes.length;
				settingsPalette.getPalette().setTo(next);
				messageView.shortMessage(Palette.palettes[next].name);
			} else if ("mode".equals(command)) {
				setIMode((iMode + 1) % 5);
			} else if ("mirror".equals(command)) {
				webMirror = !webMirror;
				setMirror(webMirror);
			} else if ("calibrate".equals(command)) {
				calibrate(false);
			} else if ("record_start".equals(command)) {
				if (usbConnection != null && !recorder.isRecording())
					startRecording(false);
			} else if ("record_stop".equals(command)) {
				if (recorder.isRecording()) {
					stopRecording();
					publishLastWebVideo();
				}
			}
		});
	}

	private void publishLastWebVideo() {
		Uri uri = recorder.getLastFileUri();
		if (uri == null || webViewServer == null)
			return;
		new Thread(() -> {
			try (InputStream in = getContentResolver().openInputStream(uri)) {
				if (in == null)
					return;
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				byte[] buffer = new byte[64 * 1024];
				int count;
				while ((count = in.read(buffer)) != -1)
					out.write(buffer, 0, count);
				webViewServer.publishVideo(out.toByteArray());
			} catch (IOException e) {
				Log.w("inficam", "Unable to prepare Web Control MP4", e);
			}
		}, "InfiCam web video").start();
	}

	private void toggleTimeChart() {
		if (timeChartState == 0) {
			timeChartState = 1;
			timeChart.start(overlayData.tempUnit, overlayData.showMax,
					 overlayData.showMin, overlayData.showCenter,
					 (long) (chartSampleRateSeconds * 1_000_000_000L));
			timeChart.setVisibility(View.VISIBLE);
			/* The chart is a foreground canvas; explicitly keep the control strips
			 * above it when its height changes. */
			if (buttonsLeft != null)
				buttonsLeft.bringToFront();
			if (buttonsRight != null)
				buttonsRight.bringToFront();
			buttonTimeChart.setColorFilter(Color.RED);
			updateTimeChartLayout();
		} else if (timeChartState == 1) {
			timeChartState = 2;
			timeChart.stop();
			buttonTimeChart.setColorFilter(Color.YELLOW);
		} else {
			timeChartState = 0;
			timeChart.setVisibility(View.GONE);
			buttonTimeChart.setColorFilter(null);
			updateTimeChartLayout();
		}
	}

	private int dp(float value) {
		return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
	}

	/** Keep the chart and camera in separate vertical regions. */
	private void updateTimeChartLayout() {
		if (cameraContainer == null)
			return;
		boolean landscape = getResources().getConfiguration().orientation ==
				Configuration.ORIENTATION_LANDSCAPE;
		if (timeChart != null) {
			ConstraintLayout.LayoutParams chartParams =
					(ConstraintLayout.LayoutParams) timeChart.getLayoutParams();
			if (landscape && timeChartState != 0) {
				/* In landscape the chart is a right-hand pane, not a bottom strip. */
				chartParams.width = 0;
				chartParams.height = 0;
				chartParams.startToStart = ConstraintLayout.LayoutParams.UNSET;
				chartParams.startToEnd = R.id.cameraContainer;
				chartParams.endToStart = R.id.buttonsRight;
				chartParams.endToEnd = ConstraintLayout.LayoutParams.UNSET;
				chartParams.topToTop = R.id.mainLayout;
				chartParams.bottomToBottom = R.id.mainLayout;
				chartParams.bottomMargin = 0;
				chartParams.leftMargin = 0;
				chartParams.rightMargin = 0;
			} else {
				chartParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
				chartParams.height = dp(TIME_CHART_HEIGHT_DP);
				chartParams.startToStart = R.id.mainLayout;
				chartParams.startToEnd = ConstraintLayout.LayoutParams.UNSET;
				chartParams.endToStart = ConstraintLayout.LayoutParams.UNSET;
				chartParams.endToEnd = R.id.mainLayout;
				chartParams.topToTop = ConstraintLayout.LayoutParams.UNSET;
				chartParams.topToBottom = ConstraintLayout.LayoutParams.UNSET;
				chartParams.bottomToBottom = R.id.mainLayout;
				chartParams.bottomMargin = timeChartState == 0 ? 0 :
						dp(TIME_CHART_BUTTON_RESERVE_DP + TIME_CHART_BOTTOM_GAP_DP);
				chartParams.leftMargin = 0;
				chartParams.rightMargin = 0;
			}
			timeChart.setLayoutParams(chartParams);
		}
		ConstraintLayout.LayoutParams cameraParams =
				(ConstraintLayout.LayoutParams) cameraContainer.getLayoutParams();
		if (landscape && timeChartState != 0) {
			cameraParams.width = 0;
			cameraParams.height = 0;
			cameraParams.startToStart = R.id.mainLayout;
			cameraParams.endToStart = R.id.timeChart;
			cameraParams.endToEnd = ConstraintLayout.LayoutParams.UNSET;
			cameraParams.topToTop = R.id.mainLayout;
			cameraParams.bottomToBottom = R.id.mainLayout;
			cameraParams.bottomMargin = 0;
			cameraParams.leftMargin = 0;
			cameraParams.rightMargin = 0;
		} else {
			cameraParams.width = 0;
			cameraParams.height = 0;
			cameraParams.startToStart = R.id.mainLayout;
			cameraParams.endToStart = ConstraintLayout.LayoutParams.UNSET;
			cameraParams.startToEnd = ConstraintLayout.LayoutParams.UNSET;
			cameraParams.endToEnd = R.id.mainLayout;
			cameraParams.topToTop = R.id.mainLayout;
			cameraParams.topToBottom = ConstraintLayout.LayoutParams.UNSET;
			cameraParams.bottomToBottom = R.id.mainLayout;
			cameraParams.bottomMargin = timeChartState == 0 ? 0 : dp(TIME_CHART_HEIGHT_DP +
					TIME_CHART_BUTTON_RESERVE_DP + TIME_CHART_BOTTOM_GAP_DP);
			cameraParams.leftMargin = 0;
			cameraParams.rightMargin = 0;
		}
		cameraContainer.setLayoutParams(cameraParams);
		/* setLayoutParams() can refresh ConstraintLayout's child order.  Keep the
		 * bottom controls above the chart after every such refresh. */
		if (buttonsLeft != null)
			buttonsLeft.bringToFront();
		if (buttonsRight != null)
			buttonsRight.bringToFront();
	}

	private void overTempLockout() {
		if (overTempLockoutActive)
			return;
		overTempLockoutActive = true;
		messageView.showMessage(R.string.msg_overtemp);
		infiCam.lockShutter();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// TODO this is probably bad
		Thread.setDefaultUncaughtExceptionHandler((paramThread, paramThrowable) -> {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			paramThrowable.printStackTrace(pw);
			Intent sendIntent = new Intent();
			sendIntent.setAction(Intent.ACTION_SEND);
			sendIntent.putExtra(Intent.EXTRA_TEXT, sw.toString());
			sendIntent.setType("text/plain");
			Intent shareIntent = Intent.createChooser(sendIntent,
					"Inficam has crashed, share crash dump?");
			startActivity(shareIntent);
			System.exit(2);
		});

		setContentView(R.layout.activity_main);
		cameraView = findViewById(R.id.cameraView);
		messageView = findViewById(R.id.message);
		surfaceMuxer = new SurfaceMuxer(this);

		/* Create and set up the InputSurface for thermal image, imode setting is not final. */
		inputSurface = new SurfaceMuxer.InputSurface(surfaceMuxer);
		thruSurface = new SurfaceMuxer.ThroughSurface(surfaceMuxer);

		cameraView.getHolder().addCallback(surfaceHolderCallback);

		/* Create and set up the Overlays. */
		overlayScreen = new Overlay(this,
				new SurfaceMuxer.InputSurface(surfaceMuxer));
		overlayRecord = new Overlay(this,
				new SurfaceMuxer.InputSurface(surfaceMuxer));
		overlayPicture = new Overlay(this,
				new SurfaceMuxer.InputSurface(surfaceMuxer));
		overlayWeb = new Overlay(this,
				new SurfaceMuxer.InputSurface(surfaceMuxer));
		outWeb = new SurfaceMuxer.OutputSurface(surfaceMuxer, null, 640, 480);
		webViewServer = new WebViewServer();
		webViewServer.setCommandHandler(this::handleWebCommand);

		/* We use it later. */
		videoSurface = new SurfaceMuxer.InputSurface(surfaceMuxer);

		/* This one runs when the camera settings have ACTUALLY been changed. */
		infiCam.setSettingsCallback(settingsCallback);

		cameraView.setOnClickListener(view -> {
			/* Allow to retry if connecting failed or permission denied. */
			if (usbConnection == null) {
				usb_device = null;
				usbMonitor.scan();
			}
		});
		cameraView.setOnTouchListener(new View.OnTouchListener() {
			private boolean paletteTouch;
			@Override public boolean onTouch(View view, android.view.MotionEvent event) {
				if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
					getRect(rect, view.getWidth(), view.getHeight());
					paletteTouch = overlayScreen.isPaletteHit((int) event.getX(), (int) event.getY(),
							rect, overlayData.showPalette);
					return paletteTouch;
				}
				if (paletteTouch && event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
					paletteTouch = false;
					showPaletteRangePopup();
					return true;
				}
				return paletteTouch;
			}
		});
		final ScaleGestureDetector.OnScaleGestureListener scaleListener =
			new ScaleGestureDetector.OnScaleGestureListener() {
				private float scaleStart;

			@Override
			public boolean onScale(ScaleGestureDetector scaleGestureDetector) {
				TextView zl = findViewById(R.id.zoomLevel);
				scale = scaleStart * scaleGestureDetector.getScaleFactor();
				if (scale < 1.0f) {
					scale = 1.0f;
					zl.setVisibility(View.INVISIBLE);
				} else zl.setVisibility(View.VISIBLE);
				if (scale >= 10.0f)
					scale = 10.0f;
				overlayData.scale = scale;
				thruSurface.scale_x = thruSurface.scale_y = scale;
				messageView.shortMessage(getString(R.string.msg_zoom, (int) (scale * 100.0f)));
				zl.setText(getString(R.string.zoomlevel, (int) (scale * 100.0f)));
				return false;
			}

			@Override
			public boolean onScaleBegin(@NonNull ScaleGestureDetector scaleGestureDetector) {
				scaleStart = scale;
				return true;
			}

			@Override
			public void onScaleEnd(@NonNull ScaleGestureDetector scaleGestureDetector) { /* Empty. */ }
		};
		cameraView.setScaleListener(scaleListener);

		ImageButton buttonShutter = findViewById(R.id.buttonShutter);

		buttonShutter.setOnClickListener(view -> {
			overTempLockoutActive = false;
			buttonShutter.setColorFilter(Color.RED);
			view.postDelayed(() -> buttonShutter.setColorFilter(null), 500);
			infiCam.unlockShutter(); //unlock shutter if locked
			calibrate(false);
		});

		buttonPhoto = findViewById(R.id.buttonPhoto);
		buttonPhoto.setOnClickListener(view -> {
			if (usbConnection != null) {
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
					askPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, granted -> {
						if (!granted) {
							messageView.showMessage(R.string.msg_permdenied_storage);
							return;
						}
						takePic = true;
					});
				} else takePic = true;
			}
		});

		buttonShare = findViewById(R.id.buttonShare);
		buttonShare.setOnClickListener(view -> {
			if (usbConnection != null && !sharePic) {
				sharePic = true;
				buttonShare.setEnabled(false);
				buttonShare.setColorFilter(Color.RED);
			}
		});

		buttonWebView = findViewById(R.id.buttonWebView);
		webViewAddress = findViewById(R.id.webViewAddress);
		buttonWebView.setOnClickListener(view -> toggleWebView());

		timeChart = findViewById(R.id.timeChart);
		cameraContainer = findViewById(R.id.cameraContainer);
		buttonTimeChart = findViewById(R.id.buttonTimeChart);
		buttonTimeChart.setOnClickListener(view -> toggleTimeChart());

		ImageButton buttonPalette = findViewById(R.id.buttonPalette);
		buttonPalette.setOnClickListener(view -> {
			settingsPalette.getPalette().setTo((settingsPalette.getPalette().get() + 1) %
					settingsPalette.getPalette().getItems().length);
			messageView.showMessage(Palette.palettes[settingsPalette.getPalette().get()].name);
		});
		buttonPalette.setOnLongClickListener(view -> {
			showSettings(settingsPalette);
			return true;
		});

		ImageButton buttonLock = findViewById(R.id.buttonLock);
			buttonLock.setOnClickListener(view -> {
			synchronized (frameLock) {
				if (isNaN(overlayData.rangeMin) && isNaN(overlayData.rangeMax)) { //range is not set
					if (overlayData.mmac == null || isNaN(overlayData.mmac.min) ||
							isNaN(overlayData.mmac.max)) {
						messageView.showMessage(R.string.msg_no_frame);
						return;
					}
					overlayData.rangeMin = overlayData.mmac.min;
					overlayData.rangeMax = overlayData.mmac.max;
					buttonLock.setImageResource(R.drawable.ic_baseline_lock_24);
					rangeSlider.setVisibility(View.VISIBLE);
					float start = settingsTherm.getRange()[0];
					float end = settingsTherm.getRange()[1];
					if (overlayData.rangeMin < start)
						start = (float) floor(overlayData.rangeMin);
					if (overlayData.rangeMax > end)
						end = (float) ceil(overlayData.rangeMax);
					if (isNaN(overlayData.rangeMin) || isInfinite(overlayData.rangeMin))
						overlayData.rangeMin = start;
					if (isNaN(overlayData.rangeMax) || isInfinite(overlayData.rangeMax))
						overlayData.rangeMax = end;
					rangeSlider.setValueFrom(start);
					rangeSlider.setValueTo(end);
					rangeSlider.setValues(overlayData.rangeMin, overlayData.rangeMax);
				} else {
					overlayData.rangeMin = overlayData.rangeMax = NaN;
					buttonLock.setImageResource(R.drawable.ic_baseline_lock_open_24);
					rangeSlider.setVisibility(View.GONE);
				}
			}
		});

		rangeSlider = findViewById(R.id.rangeSlider);
		rangeSlider.setStepSize(1.0f);
		rangeSlider.addOnChangeListener((slider, value, fromUser) -> {
			List<Float> v = rangeSlider.getValuesCorrected();
			if (v.size() < 2 || !fromUser)
				return;
			if (value == slider.getValues().get(0))
				overlayData.rangeMin = v.get(0);
			else overlayData.rangeMax = v.get(1);
		});

		ImageButton buttonVideo = findViewById(R.id.buttonVideo);
		buttonVideo.setOnClickListener(view -> toggleRecording());

		dialogBackground = findViewById(R.id.dialogBackground);
		dialogBackground.setOnClickListener(view -> hideSettingsDialog());
		settings = findViewById(R.id.settings);
		settings.init(this);
		settingsTherm = findViewById(R.id.settingsTherm); //This one has to be initialized later when we know the camera model
		settingsMeasure = findViewById(R.id.settingsMeasure);
		settingsMeasure.init(this);
		settingsPalette = findViewById(R.id.settingsPalette);
		settingsPalette.init(this);

		ImageButton buttonSettings = findViewById(R.id.buttonSettings);
		buttonSettings.setOnClickListener(view -> showSettings(settings));

		ImageButton buttonSettingsTherm = findViewById(R.id.buttonSettingsTherm);
		buttonSettingsTherm.setOnClickListener(view -> showSettings(settingsTherm));

		ImageButton buttonSettingsMeasure = findViewById(R.id.buttonSettingsMeasure);
		buttonSettingsMeasure.setOnClickListener(view -> showSettings(settingsMeasure));

		ImageButton buttonGallery = findViewById(R.id.buttonGallery);
		buttonGallery.setOnClickListener(view -> {
			buttonGallery.setColorFilter(Color.RED);
			view.postDelayed(() -> buttonGallery.setColorFilter(null), 200);

			String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
					? Manifest.permission.READ_MEDIA_IMAGES
					: Manifest.permission.READ_EXTERNAL_STORAGE;

			askPermission(perm, granted -> {
				if (!granted) {
					messageView.showMessage(R.string.msg_permdenied_storage);
					return;
				}
				Util.openGallery(this, msg -> messageView.showMessage(msg));
			});
		});

		buttonsLeft = findViewById(R.id.buttonsLeft);
		buttonsRight = findViewById(R.id.buttonsRight);
		buttonsLeftLayout = (ConstraintLayout.LayoutParams) buttonsLeft.getLayoutParams();
		buttonsRightLayout = (ConstraintLayout.LayoutParams) buttonsRight.getLayoutParams();
		/* The initial display callback is not guaranteed to fire after setContentView().
		 * Apply the portrait/landscape constraints once all control views exist. */
		updateOrientation();
	}

	@Override
	protected void onStart() {
		super.onStart();
		activityStarted = true;
		settings.load();
		settingsMeasure.load();
		settingsPalette.load();
		DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
		displayManager.registerDisplayListener(displayListener, handler);
		IntentFilter batIFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		Intent batteryStatus;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			batteryStatus = registerReceiver(
				batteryRecevier,
				batIFilter,
				Context.RECEIVER_NOT_EXPORTED
			);
		} else {
			batteryStatus = registerReceiver(batteryRecevier, batIFilter);
		}
		if(batteryStatus != null) {
			updateBatLevel(batteryStatus);
		}

		/* Beware that we can't call these in onResume as they'll ask permission with dialogs and
		 *	 thus trigger another onResume().
		 */
		usbMonitor.start(this);

		imgCompressThread = new ImgCompressThread();
		imgCompressThread.start();
	}

	@Override
	protected void onResume() {
		super.onResume();
		try {
			surfaceMuxer.init();
			renderingEnabled = true;
			/* Reconnect only after every EGL surface has been restored. Starting the USB
			 * connect thread from onStart() races with init() when returning from background. */
			usbMonitor.scan();
		} catch (RuntimeException e) {
			renderingEnabled = false;
			Log.e("inficam", "Unable to restore graphics context", e);
		}
	}

	@Override
	protected void onPause() {
		renderingEnabled = false;
		handler.removeCallbacks(renderFrameRunnable);
		synchronized (frameLock) {
			renderPending = false;
	}
		try {
			surfaceMuxer.deinit();
		} catch (RuntimeException e) {
			Log.w("inficam", "Ignoring graphics cleanup error", e);
		}
		takePic = false;
		super.onPause();
	}

	@Override
	protected void onStop() {
		activityStarted = false;
		handler.removeCallbacks(reconnectRunnable);
		if (imgCompressThread != null) {
			imgCompressThread.shutdown();
			imgCompressThread = null;
		}
		unregisterReceiver(batteryRecevier);
		DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
		displayManager.unregisterDisplayListener(displayListener);
		stopRecording();
		disconnect();
		if (webViewServer != null)
			webViewServer.stop();
		if (webViewAddress != null)
			webViewAddress.setVisibility(View.GONE);
		if (buttonWebView != null)
			buttonWebView.setColorFilter(null);
		usbMonitor.stop();
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		if (outWeb != null) {
			outWeb.release();
			outWeb = null;
		}
		surfaceMuxer.release();
		super.onDestroy();
	}

	@Override
	public void onBackPressed() {
		if (dialogBackground.getVisibility() == View.VISIBLE)
			hideSettingsDialog();
		else super.onBackPressed();
	}

	@SuppressLint("SourceLockedOrientationActivity")
	private void updateOrientation() { /* Called on start by SettingsMain. */
		if (rangeSlider == null || buttonsLeft == null || buttonsRight == null ||
				cameraContainer == null)
			return;
		WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
		orientation = wm.getDefaultDisplay().getRotation();
		boolean landscapeLayout = getResources().getConfiguration().orientation ==
				Configuration.ORIENTATION_LANDSCAPE;
		ConstraintLayout.LayoutParams rlp = (ConstraintLayout.LayoutParams) rangeSlider.getLayoutParams();
		if (!landscapeLayout) {
			thruSurface.rotate90 = true;
			buttonsLeft.setOrientation(LinearLayout.HORIZONTAL);
			buttonsRight.setOrientation(LinearLayout.HORIZONTAL);
			/* Recreate these params instead of reusing a portrait/landscape params object;
			 * Android can retain the previous zero height when the activity is resumed. */
			buttonsLeftLayout = new ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			buttonsRightLayout = new ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			/* Both horizontal edges are constrained; 0dp lets ConstraintLayout resolve the
			 * available width reliably after a reconnect/configuration change. */
			buttonsLeftLayout.topToTop = R.id.mainLayout;
			buttonsLeftLayout.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
			buttonsLeftLayout.topToBottom = ConstraintLayout.LayoutParams.UNSET;
			buttonsLeftLayout.leftToLeft = R.id.mainLayout;
			buttonsLeftLayout.rightToRight = R.id.mainLayout;
			buttonsLeftLayout.startToStart = ConstraintLayout.LayoutParams.UNSET;
			buttonsLeftLayout.endToEnd = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.width = ViewGroup.LayoutParams.WRAP_CONTENT;
			buttonsRightLayout.height = ViewGroup.LayoutParams.WRAP_CONTENT;
			buttonsRightLayout.topToTop = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.bottomToBottom = R.id.mainLayout;
			buttonsRightLayout.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.leftToLeft = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.rightToRight = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.startToStart = R.id.mainLayout;
			buttonsRightLayout.endToEnd = R.id.mainLayout;
			buttonsLeft.setLayoutParams(buttonsLeftLayout);
			buttonsRight.setLayoutParams(buttonsRightLayout);
			buttonsLeft.setLayoutParams(buttonsLeftLayout);
			buttonsRight.setLayoutParams(buttonsRightLayout);
			buttonsLeft.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
			buttonsRight.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
			rlp.rightToLeft = ConstraintLayout.LayoutParams.UNSET;
			rlp.rightToRight = ConstraintLayout.LayoutParams.UNSET;
			rlp.topToTop = ConstraintLayout.LayoutParams.UNSET;
			//noinspection SuspiciousNameCombination
			rlp.topToBottom = R.id.buttonsLeft;
			rlp.leftToRight = ConstraintLayout.LayoutParams.UNSET;
			rlp.leftToLeft = R.id.mainLayout;
			rlp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
			rlp.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
			rlp.width = WindowManager.LayoutParams.MATCH_PARENT;
			rlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
			rangeSlider.setLayoutParams(rlp);
			rangeSlider.setVertical(false);
		} else {
			thruSurface.rotate90 = false;
			buttonsLeft.setOrientation(LinearLayout.VERTICAL);
			buttonsRight.setOrientation(LinearLayout.VERTICAL);
			/* Recreate both side panels for landscape.  Reusing the portrait
			 * params leaves a zero height after a rotation, which clips all icons. */
			buttonsLeftLayout = new ConstraintLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
			buttonsRightLayout = new ConstraintLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
			buttonsLeftLayout.topToTop = R.id.mainLayout;
			buttonsLeftLayout.bottomToBottom = R.id.mainLayout;
			buttonsLeftLayout.topToBottom = ConstraintLayout.LayoutParams.UNSET;
			buttonsLeftLayout.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
			buttonsLeftLayout.leftToLeft = R.id.mainLayout;
			buttonsLeftLayout.rightToRight = ConstraintLayout.LayoutParams.UNSET;
			buttonsLeftLayout.startToStart = ConstraintLayout.LayoutParams.UNSET;
			buttonsLeftLayout.endToEnd = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.topToTop = R.id.mainLayout;
			buttonsRightLayout.bottomToBottom = R.id.mainLayout;
			buttonsRightLayout.topToBottom = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.leftToLeft = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.rightToRight = R.id.mainLayout;
			buttonsRightLayout.startToStart = ConstraintLayout.LayoutParams.UNSET;
			buttonsRightLayout.endToEnd = ConstraintLayout.LayoutParams.UNSET;
			rlp.topToTop = R.id.mainLayout;
			rlp.topToBottom = ConstraintLayout.LayoutParams.UNSET;
			rlp.leftToRight = ConstraintLayout.LayoutParams.UNSET;
			rlp.rightToLeft = ConstraintLayout.LayoutParams.UNSET;
			rlp.leftToLeft = ConstraintLayout.LayoutParams.UNSET;
			rlp.width = WindowManager.LayoutParams.WRAP_CONTENT;
			rlp.height = WindowManager.LayoutParams.MATCH_PARENT;
			if (swapControls) {
				rlp.rightToLeft = R.id.buttonsLeft;
				ConstraintLayout.LayoutParams leftParams = buttonsLeftLayout;
				ConstraintLayout.LayoutParams rightParams = buttonsRightLayout;
				leftParams.leftToLeft = ConstraintLayout.LayoutParams.UNSET;
				leftParams.rightToRight = R.id.mainLayout;
				rightParams.leftToLeft = R.id.mainLayout;
				rightParams.rightToRight = ConstraintLayout.LayoutParams.UNSET;
				buttonsLeft.setLayoutParams(leftParams);
				buttonsRight.setLayoutParams(rightParams);
				buttonsLeft.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
				buttonsRight.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
			} else {
				rlp.leftToRight = R.id.buttonsLeft;
				buttonsLeft.setLayoutParams(buttonsLeftLayout);
				buttonsRight.setLayoutParams(buttonsRightLayout);
				buttonsLeft.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
				buttonsRight.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
			}
			rangeSlider.setLayoutParams(rlp);
			rangeSlider.setVertical(true);
		}
		synchronized (frameLock) {
			overlayData.rotate90 = orientation == Surface.ROTATION_0 ||
					orientation == Surface.ROTATION_180;
			if (orientation == Surface.ROTATION_270 || orientation == Surface.ROTATION_180) {
				overlayData.rotate = !rotate;
				thruSurface.rotate = !rotate;
			} else {
				overlayData.rotate = rotate;
				thruSurface.rotate = rotate;
			}
		}
		updateTimeChartLayout();
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		/* Configuration is authoritative for layout direction; the display rotation
		 * callback can arrive one frame earlier while returning from landscape. */
		if (rangeSlider != null)
			handler.post(this::updateOrientation);
	}

	private void hideSettingsDialog() {
		boolean wasThermDialog = activeSettingsDialog == settingsTherm;
		activeSettingsDialog = null;
		dialogBackground.setVisibility(View.GONE);
		boolean changedThermSettings = wasThermDialog && settingsTherm.endDeferredCameraUpdates();
		boolean pendingNativeCalibration = wasThermDialog &&
				infiCam.setCalibrationSuppressed(false);
		if (changedThermSettings || pendingCalibrationAfterThermDialog ||
				pendingNativeCalibration) {
			pendingCalibrationAfterThermDialog = false;
			calibrate(false);
		}
	}

	private void scheduleReconnect(long delayMs) {
		handler.removeCallbacks(reconnectRunnable);
		if (activityStarted)
			handler.postDelayed(reconnectRunnable, delayMs);
	}

	private void showSettings(Settings settings) {
		if (activeSettingsDialog == settingsTherm && activeSettingsDialog != settings) {
			activeSettingsDialog = null;
			boolean changedThermSettings = settingsTherm.endDeferredCameraUpdates();
			boolean pendingNativeCalibration = infiCam.setCalibrationSuppressed(false);
			if (changedThermSettings || pendingCalibrationAfterThermDialog ||
					pendingNativeCalibration) {
				pendingCalibrationAfterThermDialog = false;
				calibrate(false);
			}
		}
		FrameLayout dialogs = dialogBackground.findViewById(R.id.dialogs);
		for (int i = 0; i < dialogs.getChildCount(); ++i)
			dialogs.getChildAt(i).setVisibility(View.GONE);
		settings.setVisibility(View.VISIBLE);
		activeSettingsDialog = settings;
		if (settings == settingsTherm) {
			settingsTherm.beginDeferredCameraUpdates();
			infiCam.setCalibrationSuppressed(true);
		}
		dialogBackground.setVisibility(View.VISIBLE);
		TextView title = findViewById(R.id.dialogTitle);
		title.setText(settings.getName());
	}

	private void disconnect() {
		acceptCameraSettings = false;
		sharePic = false;
		if (buttonShare != null) {
			buttonShare.setEnabled(true);
			buttonShare.setColorFilter(null);
		}
		connectGeneration++;
		setCalibrationUi(false);
		overTempLockoutActive = false;
		stopRecording();
		disconnecting = true;
		UsbDeviceConnection oldConnection;
		synchronized (usbLifecycleLock) {
			oldConnection = usbConnection;
			usbConnection = null;
			usb_device = null;
			try {
				infiCam.setFrameCallback(null); // disable frames before stopping native stream
				infiCam.stopStream();
				infiCam.disconnect();
			} catch (RuntimeException e) {
				Log.w("inficam", "Ignoring error while disconnecting USB camera", e);
			}
		}
		if (oldConnection != null) {
			try { oldConnection.close(); } catch (RuntimeException e) {
				Log.w("inficam", "Ignoring error while closing USB connection", e);
			}
		}
		messageView.setMessage(R.string.msg_disconnected);
	}

	private void toggleRecording() {
		if (!recorder.isRecording() && usbConnection != null) {
			askPermission(Manifest.permission.CAMERA, granted -> {
				if (granted) {
					if (!recordAudio) {
						startRecording(false);
						return;
					}
					askPermission(Manifest.permission.RECORD_AUDIO, audiogranted -> {
						if (!audiogranted) {
							messageView.showMessage(R.string.msg_permdenied_audio);
							return;
						}
						startRecording(recordAudio);
					});
				} else messageView.showMessage(R.string.msg_permdenied_cam);
			});
		} else stopRecording();
	}

	private void startRecording(boolean recordAudio) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			askPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, granted -> {
				if (!granted)
					messageView.showMessage(R.string.msg_permdenied_storage);
				else _startRecording(recordAudio);
			});
		} else _startRecording(recordAudio);
	}

	/* Request audio permission first when necessary! */
	private void _startRecording(boolean recordAudio) {
		try {
			int w = vidWidth, h = vidHeight;
			if (orientation == Surface.ROTATION_0 || orientation == Surface.ROTATION_180) {
				h ^= w;
				w ^= h;
				h ^= w;
			}
			Surface rsurface = recorder.start(this, w, h, recordAudio);
			outRecord = new SurfaceMuxer.OutputSurface(surfaceMuxer, rsurface);
			outRecord.setSize(w, h);
			overlayRecord.setSize(w, h);
			if (exportChartSeparately && timeChartState != 0 && timeChart != null) {
				/* The separate still image is the chart view itself. Use that same aspect ratio
				 * for video, with even dimensions required by common H.264 encoders. */
				int chartWidth = evenVideoDimension(timeChart.getWidth(), w);
				int chartHeight = evenVideoDimension(timeChart.getHeight(), h);
				Surface chartSurface = chartRecorder.start(this, chartWidth, chartHeight, false);
				outChartRecord = new SurfaceMuxer.OutputSurface(surfaceMuxer, chartSurface);
				outChartRecord.setSize(chartWidth, chartHeight);
			}
			ImageButton buttonVideo = findViewById(R.id.buttonVideo);
			buttonVideo.setColorFilter(Color.RED);
		} catch (IOException e) {
			e.printStackTrace();
			messageView.showMessage(R.string.msg_failrecord);
		}
	}

	private static int evenVideoDimension(int preferred, int fallback) {
		int value = preferred > 1 ? preferred : fallback;
		return Math.max(2, value & ~1);
	}

	private void stopRecording() {
		ImageButton buttonVideo = findViewById(R.id.buttonVideo);
		buttonVideo.clearColorFilter();
		recorder.stop();
		chartRecorder.stop();
		if (outRecord != null) {
			outRecord.release();
			outRecord = null;
		}
		if (outChartRecord != null) {
			outChartRecord.release();
			outChartRecord = null;
		}
	}

	public void updateBatLevel(Intent batteryStatus) {
		int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
		boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
				status == BatteryManager.BATTERY_STATUS_FULL;
		int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
		int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
		BatteryLevel batLevel = findViewById(R.id.batLevel);
		batLevel.setLevel(scale, level, isCharging);
	}

	/*
	 * Following are routines called by the settings class.
	 */

	public void setIMode(int value) { iMode = value; }
	public void setSharpening(float value) { inputSurface.sharpening = value; }

	public void setRecordAudio(boolean value) { recordAudio = value; }

	public void setChartSampleRate(float value) {
		chartSampleRateSeconds = Math.max(1.0f / 25.0f, Math.min(1800.0f, value));
	}

	public void setExportChartSeparately(boolean value) { exportChartSeparately = value; }

	public void setSwapControls(boolean value) {
		swapControls = value;
		updateOrientation();
	}

	public void setApplyLocalCorrection(boolean value) {
		applyLocalCorrection = value;
	}

	public void setLocalCorrection(float value) {
		localCorrection = value;
	}

	public void setShowBatLevel(boolean value) {
		BatteryLevel batLevel = findViewById(R.id.batLevel);
		batLevel.setVisibility(value ? View.VISIBLE : View.GONE);
	}

	public void setRotate(boolean value) {
		rotate = value;
		updateOrientation();
	}

	public void setMirror(boolean value) {
		synchronized (frameLock) {
			overlayData.mirror = value;
			thruSurface.mirror = value;
		}
	}

	public void setShowCenter(boolean value) {
		synchronized (frameLock) {
			overlayData.showCenter = value;
		}
	}

	public void setShowMax(boolean value) {
		synchronized (frameLock) {
			overlayData.showMax = value;
		}
	}

	public void setShowMin(boolean value) {
		synchronized (frameLock) {
			overlayData.showMin = value;
		}
	}

	public void setShowPalette(boolean value) {
		synchronized (frameLock) {
			overlayData.showPalette = value;
		}
	}

	public void setPaletteMin(float value) {
		paletteManualMin = value;
		if (!(paletteManualMax > paletteManualMin))
			paletteManualMax = paletteManualMin + 0.1f;
		applyManualPaletteRange();
	}

	public void setPaletteMax(float value) {
		paletteManualMax = value;
		if (!(paletteManualMax > paletteManualMin))
			paletteManualMin = paletteManualMax - 0.1f;
		applyManualPaletteRange();
	}

	public void setPaletteAuto() {
		synchronized (frameLock) {
			overlayData.rangeMin = NaN;
			overlayData.rangeMax = NaN;
		}
		ImageButton lockButton = findViewById(R.id.buttonLock);
		if (lockButton != null)
			lockButton.setImageResource(R.drawable.ic_baseline_lock_open_24);
		if (rangeSlider != null)
			rangeSlider.setVisibility(View.GONE);
	}

	private void applyManualPaletteRange() {
		synchronized (frameLock) {
			overlayData.rangeMin = paletteManualMin;
			overlayData.rangeMax = paletteManualMax;
		}
		ImageButton lockButton = findViewById(R.id.buttonLock);
		if (lockButton != null)
			lockButton.setImageResource(R.drawable.ic_baseline_lock_open_24);
		if (rangeSlider != null)
			rangeSlider.setVisibility(View.GONE);
	}

	private void showPaletteRangePopup() {
		LinearLayout content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		int pad = dp(8);
		content.setPadding(pad, 0, pad, 0);
		EditText minInput = new EditText(this);
		EditText maxInput = new EditText(this);
		minInput.setSingleLine(true);
		maxInput.setSingleLine(true);
		minInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL |
				InputType.TYPE_NUMBER_FLAG_SIGNED);
		maxInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL |
				InputType.TYPE_NUMBER_FLAG_SIGNED);
		minInput.setHint(getString(R.string.set_palette_min));
		maxInput.setHint(getString(R.string.set_palette_max));
		float min = Float.isNaN(overlayData.rangeMin) ? paletteManualMin : overlayData.rangeMin;
		float max = Float.isNaN(overlayData.rangeMax) ? paletteManualMax : overlayData.rangeMax;
		minInput.setText(String.format(Locale.US, "%.2f", min));
		maxInput.setText(String.format(Locale.US, "%.2f", max));
		content.addView(minInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		content.addView(maxInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.palette_range_title)
				.setView(content)
				.setNeutralButton(R.string.set_palette_auto, null)
				.create();
		dialog.setOnShowListener(ignored -> {
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
				settingsPalette.setAutoRangeMode();
				dialog.dismiss();
			});
			android.text.TextWatcher watcher = new android.text.TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
				@Override public void onTextChanged(CharSequence s, int st, int before, int count) { }
				@Override public void afterTextChanged(android.text.Editable e) {
				try {
					float enteredMin = Float.parseFloat(minInput.getText().toString());
					float enteredMax = Float.parseFloat(maxInput.getText().toString());
					if (!Float.isFinite(enteredMin) || !Float.isFinite(enteredMax) ||
							enteredMax <= enteredMin) return;
					settingsPalette.setManualValues(enteredMin, enteredMax);
					setPaletteMin(enteredMin);
					setPaletteMax(enteredMax);
				} catch (NumberFormatException ignored) { }
				}
			};
			minInput.addTextChangedListener(watcher);
			maxInput.addTextChangedListener(watcher);
		});
		dialog.show();
	}

	public void setPicSize(int w, int h) {
		picWidth = w; /* No need to sync, only used on UI thread. */
		picHeight = h;
	}

	public void setVidSize(int w, int h) {
		vidWidth = w;
		vidHeight = h;
	}

	public void setOrientation(int i) {
		setRequestedOrientation(i);
		updateOrientation();
	}

	public void setImgType(int i) {
		if (imgCompressThread != null)
			imgCompressThread.lock.lock();
		imgType = i;
		if (imgCompressThread != null)
			imgCompressThread.lock.unlock();
	}

	public void setImgQuality(int i) {
		if (imgCompressThread != null)
			imgCompressThread.lock.lock();
		imgQuality = i;
		if (imgCompressThread != null)
			imgCompressThread.lock.unlock();
	}

	public void setTempUnit(int i) {
		synchronized (frameLock) {
			overlayData.tempUnit = i;
		}
		settings.setTempUnit(i);
		settingsTherm.setTempUnit(i);
		settingsMeasure.setTempUnit(i);
		settingsPalette.setTempUnit(i);
	}

	public void calibrate(boolean blocking) {
		if(blocking){
			infiCam.calibrateBlocking();
		} else {
			if (suppressCalibrationRequest)
				return;
			if (activeSettingsDialog == settingsTherm) {
				pendingCalibrationAfterThermDialog = true;
				return;
			}
			setCalibrationUi(true);
			infiCam.calibrate();
			waitForCalibrationDone();
		}
	}
}
