package be.ntmn.inficam;

import android.hardware.usb.UsbDevice;

import java.util.Locale;

import be.ntmn.libinficam.InfiCam;

/** InfiCam backend adapter. No hardware-specific code leaks into the calibration engine. */
final class InfiCamThermalCameraHal implements ThermalCameraHal {
	private final InfiCam camera;
	private final String physicalDeviceId;
	private volatile boolean valid = true;

	InfiCamThermalCameraHal(InfiCam camera, UsbDevice device) {
		this.camera = camera;
		this.physicalDeviceId = buildPhysicalDeviceId(device);
	}

	@Override public String getPhysicalDeviceId() { return physicalDeviceId; }

	@Override public boolean isReady() {
		return valid && camera.isStreaming();
	}

	@Override public boolean canHoldShutterClosed() {
		return valid && camera.canHoldShutterForSpatialCalibration();
	}

	@Override public boolean holdShutterClosed() {
		return valid && camera.holdShutterForSpatialCalibration();
	}

	@Override public boolean restoreShutter() {
		return valid && camera.releaseShutterAfterSpatialCalibration();
	}

	@Override public void invalidate() { valid = false; }

	private static String buildPhysicalDeviceId(UsbDevice device) {
		String serial = safeString(() -> device.getSerialNumber());
		String manufacturer = safeString(() -> device.getManufacturerName());
		String product = safeString(() -> device.getProductName());
		String base = String.format(Locale.ROOT, "%04x:%04x",
				device.getVendorId(), device.getProductId());
		/* USB serial is the true physical identity. Some cameras do not expose one;
		 * the stable descriptor fingerprint is then preferable to Android's changing
		 * bus address and still keeps profiles isolated by camera model. */
		if (!serial.isEmpty())
			return base + ":serial=" + serial;
		return base + ":manufacturer=" + manufacturer + ":product=" + product;
	}

	private interface StringGetter { String get(); }

	private static String safeString(StringGetter getter) {
		try {
			String value = getter.get();
			return value == null ? "" : value.trim();
		} catch (RuntimeException ignored) {
			return "";
		}
	}
}
