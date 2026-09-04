package be.ntmn.libinficam;

/**
 * Reusable, camera-independent native CPU engine for additive spatial FPN
 * calibration. All hot-path buffers live in native memory and are reused.
 */
public final class SpatialCalibrationEngine implements AutoCloseable {
	public static final class Candidate {
		public final float[] offsets;
		public final long collectedFrames;
		public final float invalidPixelFraction;
		public final float spatialRms;
		public final double averageProcessUs;
		public final double maxProcessUs;

		private Candidate(float[] offsets, long collectedFrames,
				float invalidPixelFraction, float spatialRms,
				double averageProcessUs, double maxProcessUs) {
			this.offsets = offsets;
			this.collectedFrames = collectedFrames;
			this.invalidPixelFraction = invalidPixelFraction;
			this.spatialRms = spatialRms;
			this.averageProcessUs = averageProcessUs;
			this.maxProcessUs = maxProcessUs;
		}
	}

	static {
		System.loadLibrary("usb1.0");
		System.loadLibrary("uvc");
		System.loadLibrary("InfiCam");
	}

	private long handle;

	public SpatialCalibrationEngine() {
		handle = nativeCreate();
		if (handle == 0)
			throw new OutOfMemoryError("Unable to create spatial calibration engine.");
	}

	public synchronized boolean configure(int width, int height) {
		if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE / height)
			return false;
		return nativeConfigure(requireHandle(), width * height);
	}

	public synchronized boolean setActiveMap(float[] offsets) {
		return offsets != null && nativeSetActiveMap(requireHandle(), offsets);
	}

	public synchronized void clearActiveMap() {
		nativeClearActiveMap(requireHandle());
	}

	public synchronized void begin() {
		nativeBegin(requireHandle());
	}

	/** Observe the uncorrected frame first, then apply the previously committed map in-place. */
	public synchronized boolean processFrame(float[] temperatures, boolean collect) {
		return nativeProcess(requireHandle(), temperatures, collect);
	}

	public synchronized Candidate finishCandidate() {
		long ptr = requireHandle();
		float[] offsets = nativeFinish(ptr);
		if (offsets == null)
			return null;
		return new Candidate(offsets, nativeGetCollectedFrames(ptr),
				nativeGetInvalidFraction(ptr), nativeGetSpatialRms(ptr),
				nativeGetAverageProcessUs(ptr), nativeGetMaxProcessUs(ptr));
	}

	public synchronized void cancel() {
		if (handle != 0)
			nativeCancel(handle);
	}

	public synchronized String getLastError() {
		return handle == 0 ? "Calibration engine has been released." : nativeGetError(handle);
	}

	@Override
	public synchronized void close() {
		if (handle != 0) {
			nativeDestroy(handle);
			handle = 0;
		}
	}

	private long requireHandle() {
		if (handle == 0)
			throw new IllegalStateException("Calibration engine has been released.");
		return handle;
	}

	private static native long nativeCreate();
	private static native void nativeDestroy(long handle);
	private static native boolean nativeConfigure(long handle, int pixelCount);
	private static native boolean nativeSetActiveMap(long handle, float[] offsets);
	private static native void nativeClearActiveMap(long handle);
	private static native void nativeBegin(long handle);
	private static native void nativeCancel(long handle);
	private static native boolean nativeProcess(long handle, float[] temperatures, boolean collect);
	private static native float[] nativeFinish(long handle);
	private static native String nativeGetError(long handle);
	private static native long nativeGetCollectedFrames(long handle);
	private static native float nativeGetInvalidFraction(long handle);
	private static native float nativeGetSpatialRms(long handle);
	private static native double nativeGetAverageProcessUs(long handle);
	private static native double nativeGetMaxProcessUs(long handle);
}
