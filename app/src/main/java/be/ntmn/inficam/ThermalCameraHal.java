package be.ntmn.inficam;

/** @deprecated Used only by the disabled Spatial FPN calibration implementation. */
@Deprecated
public interface ThermalCameraHal {
	String getPhysicalDeviceId();
	boolean isReady();
	boolean canHoldShutterClosed();
	boolean holdShutterClosed();
	boolean restoreShutter();
	void invalidate();
}
