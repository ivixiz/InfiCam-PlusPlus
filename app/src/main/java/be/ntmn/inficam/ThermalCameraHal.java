package be.ntmn.inficam;

/** Hardware boundary used by spatial calibration. */
public interface ThermalCameraHal {
	String getPhysicalDeviceId();
	boolean isReady();
	boolean canHoldShutterClosed();
	boolean holdShutterClosed();
	boolean restoreShutter();
	void invalidate();
}
