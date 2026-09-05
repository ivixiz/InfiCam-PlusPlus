# InfiCamPlus

InfiCamPlus is a fork of [InfiCam](https://gitlab.com/netman69/inficam) that adds
raw-camera support, extended InfiRay P2 Pro support, temperature history charts,
improved capture/export tools, and remote control from a web browser.

## Highlights of this fork

### InfiRay P2 Pro support and USB reliability

- Detects the P2 Pro by its `0bda:5830` VID:PID even though the device reports the
  generic product name `USB Camera`.
- Implements the P2 Pro command transport based on the official InfiRay USB SDK.
- Supports both normal- and high-temperature gain modes from Thermometry Settings,
  including command readback and range validation.
- Handles the P2 Pro raw stream and calibration data, rejects incomplete frames,
  retries incomplete calibration downloads, and performs an initial shutter
  calibration to avoid a noisy image after connection.
- Recovers the USB session automatically after a temporary cable/connector contact
  loss. Disconnect, reconnect, app background/foreground transitions, and repeated
  calibration no longer leave a stale or permanently black camera surface.
- USB stream dimensions come from the connected camera. The implementation does not
  assume that every supported camera has the P2 Pro/T2S `192x256` image size.

### Time Chart

The Time Chart displays enabled temperature measurements over time:

- maximum temperature in red;
- minimum temperature in blue;
- center-point temperature in yellow.

The available series follow the selections in Measurement Settings. The chart uses
an automatically scaled major/minor grid, collision-aware time labels, adaptive time
formats from seconds to hours, and the temperature unit selected in the app.

Chart button behavior:

1. First press: create a new chart and start recording.
2. Next press: pause recording.
3. Following presses: resume/pause the same chart.
4. Long press: delete the current chart.

While paused, the on-screen chart displays a `Stopped / Click to continue / Hold to
delete` overlay. The overlay is intentionally excluded from exported pictures and
videos. Paused intervals are also removed from recorded video instead of producing
long frozen sections.

The chart sample rate can be configured from `0.04 s` (the 25 FPS camera period) to
`1800 s` (30 minutes). Long sessions are bounded to 12,000 stored points; older data
is progressively decimated while preserving the overall trend and elapsed-time
scale.

<img width="2400" height="1080" alt="Screenshot_20260830-141904104" src="https://github.com/user-attachments/assets/55b56047-8df2-4c3c-97e3-dd452d3f2e79" />

### Spatial fixed-pattern autocalibration

The **Settings → Spatial fixed-pattern autocalibration…** action creates a
per-camera additive offset map to reduce persistent pixel-to-pixel temperature
differences (spatial FPN).
Calibration takes approximately ten minutes at a stable room temperature of
20–25 °C. Cameras whose shutter can safely remain closed use it automatically.
For P2 Pro the app uses the official SDK's independent manual shutter switch,
keeps the internal shutter closed for the acquisition, and restores it on success,
cancellation, lifecycle teardown, timeout, or USB recovery. Cameras without a
safe long-duration shutter hold ask for a uniform opaque lens cover instead.

The first two minutes are reserved for thermal stabilization. The remaining frames
are processed online: every frame is referenced to the median of all finite thermal
pixels, then per-pixel residuals are accumulated with robust transient/outlier
rejection. No frames are retained in memory. This is an offset-only procedure at one
temperature; it intentionally does not attempt a gain calibration.

The offset map is applied before min/max/center measurements, Time Chart sampling,
rendering, Web Control, pictures, video, and sharing. Profiles are keyed by the
physical USB serial when available and loaded automatically after reconnect. Saving
is transactional: an existing valid profile remains active until a candidate has
passed validation and its replacement has been atomically committed. Cancellation,
frame timeout, USB loss, app shutdown, and validation errors discard the candidate
and preserve the previous profile.

The hot path is implemented as an allocation-free native CPU pass with preallocated
buffers. GPU/Vulkan processing is not required for this stage.

### Optional ESP32-S3 USB Web Control bridge

**Settings → Use ESP-32 for connection** enables a dedicated local connection for
Web Control. The phone joins the `InfiCamBridge` access point and registers the
running Web Control server with the ESP32-S3. A PC connected to the ESP USB-OTG
port receives a CDC-NCM Ethernet interface and can then open the fixed address
**http://192.168.7.1**. The bridge forwards HTTP, controls, state, MJPEG and export
downloads as TCP bytes; it does not decode or re-encode camera data.

Android displays its nearby Wi-Fi chooser on the first connection. Enable the
setting, approve `InfiCamBridge`, and start Web Control. The fixed URL is shown in
the app only after registration succeeds. Both the phone registration and the
Wi-Fi network request recover automatically after a temporary link loss. With the
setting disabled, Web Control retains its normal local-network address and
behaviour.

The ESP-IDF firmware and build instructions are in README.md of the adjacent local project
`./espbridge`.

### Pictures, video, and sharing

- The Share button captures the current thermal view and opens the Android share
  sheet.
- Pictures and MP4 recordings can include the active Time Chart.
- Combined exports place the thermal image and chart directly next to each other,
  without an intermediate black letterbox band.
- `Export Chart Separately` stores the thermal camera and chart as separate picture
  or video files. Chart aspect ratio is preserved for both still images and video.
- Camera aspect ratio is preserved for every supported sensor. The configured
  Picture/Video Resolution is used as the output bound, without distorting a native
  camera frame to a hard-coded P2 Pro size.
- Picture export follows the selected PNG, RGB565 PNG, or JPEG format and quality.

The Share action always creates one composed image, regardless of the
`Export Chart Separately` setting.

### Web Control

Web Control exposes the running app to devices on the same local network. It streams
the thermal camera, displays measurements and the Time Chart, and provides remote
control without requiring a cloud service.

Available synchronized controls include:

- Time Chart start, pause, resume, and long-press delete;
- palette selection and a dual-thumb locked palette range;
- mirror and shutter calibration;
- application Settings;
- Measurement Settings;
- Thermometry Settings, including the camera temperature range;
- phone battery state;
- Save Picture and Record Video.

The phone sends a native-resolution false-colour sensor frame. Temperature labels,
measurement markers, the palette scale, and the chart are rendered at browser/export
resolution, so they remain sharp instead of being enlarged from a low-resolution
camera overlay. Frame dimensions are read dynamically from the connected camera.

Web pictures use the format, quality, and Picture Resolution selected in the Android
app. Combined Web exports use the same orientation and proportions as the phone:
chart below a portrait camera image or beside a landscape image. MP4 recording is
performed by the phone's Android recording path and downloaded through the browser
when complete.

The stream is paced for up to 25 FPS. Actual Web FPS depends on the phone, camera,
Wi-Fi link, and browser. Browser rendering is kept separate from the phone display,
and stale streams are restarted automatically after a camera reconnection. A lost
state connection shows `Waiting for phone state...`; the message is cleared as soon
as polling succeeds again. Controls remain horizontally scrollable on narrow mobile
browsers.

<img width="1193" height="754" alt="screenshot" src="https://github.com/user-attachments/assets/ad32e119-3eff-4608-95eb-742af45d616e" />

### Starting Web Control

1. Connect the phone and viewing device to the same Wi-Fi/local network.
2. Connect the thermal camera and wait for the image/calibration to complete.
3. Press the Web Control button at the bottom of the Android app.
4. Open the displayed address, normally `http://<phone-ip>:8080/`, in a browser.
5. Press the Web Control button again to stop the local server.

Keep InfiCamPlus in the foreground while using Web Control. If the displayed address
cannot be reached, verify that both devices are on the same subnet and that the
router/access point does not use client isolation. VPN, mobile-data, or hotspot
interfaces may expose a different address than the phone's usual Wi-Fi address.

### User interface and multi-window behavior

- Portrait and landscape layouts keep the camera, chart, palette, and controls from
  overlapping.
- In landscape, the camera and chart are shown side by side.
- Android split top/bottom mode is supported. On a physically portrait phone the
  chart moves to the left and the camera to the right; the existing landscape order
  is retained when the phone itself is horizontal.
- The palette gradient can be tapped to enter manual minimum/maximum limits or return
  to automatic range mode. The lock button and Web Control range slider stay
  synchronized.
- Control icons use consistent sizing across the top and bottom rows.

## Downloads and related projects
- [InfiCam++ releases](https://github.com/diminDDL/InfiCamPlus/releases)
- [InfiCamPlus releases](https://github.com/ivixiz/InfiCam-PlusPlus/releases)
- [Original InfiCam project](https://gitlab.com/netman69/inficam)
- [Desktop Python thermal-camera tools](https://github.com/diminDDL/IR-Py-Thermal)

## Build project

* Build the debug version:

  ```bash
  ./gradlew :app:assembleDebug
  ```

* Build the release version:

  ```bash
  ./gradlew :app:assembleRelease
  ```

### Wireless installation via ADB (optional)

1. Install ADB tools:

   ```bash
   sudo apt update
   sudo apt install adb
   ```

2. Connect your phone and computer to the same Wi-Fi network.

3. On your phone, go to:

   **Settings → System → Developer options → Wireless debugging**

   and enable **Wireless debugging**.

4. Pair the phone with your computer:

   ```bash
   adb pair 192.168.0.52:XXXXX
   ```

5. Connect to the phone:

   ```bash
   adb connect 192.168.0.52:YYYYY
   ```

6. Install the debug APK:

   ```bash
   adb -s 192.168.0.52:YYYYY install -r \
     app/build/outputs/apk/debug/app-debug.apk
   ```
Alternatively, you can copy the APK to your phone and install it manually.


## Contributing

The primary language of this repository is English. Please write issues,
discussions, pull requests, and comments in English. Discussions in other languages
will be deleted.

If you wish to add support for a camera that is not yet supported, please provide the
VID and PID and any other available device information in an issue. Ideally, be
prepared to help test or implement the support: camera-specific work cannot be
validated without access to the physical device.

## Camera model support

### Legend

- ✅ Fully supported
- 🆗 Quite usable, but may have small quirks
- 🟨 Works, but has known quirks
- 🟥 Not currently supported

| Model | VID:PID | Status | Notes | More information |
| --- | --- | --- | --- | --- |
| T2S+ v1 | `1514:xxxx` | ✅ | Working in v1.0.5+. | [InfiCam](https://gitlab.com/netman69/inficam) |
| T2S+ v2 | `04b4:0100` | ✅ | Working in v1.0.4+. | [#2](https://github.com/diminDDL/InfiCamPlus/issues/2), [#18](https://github.com/diminDDL/InfiCamPlus/pull/18) |
| P2 Pro | `0bda:5830` | ✅ | Working in v1.1.0+. Tested in this fork, including raw calibration, USB recovery, and normal/high-temperature ranges. | [#1](https://github.com/diminDDL/InfiCamPlus/issues/1), [#11](https://github.com/diminDDL/InfiCamPlus/pull/11) |
| HT301 | `1514:0001` | 🟥 | Not supported at this time; PRs are welcome. | [#5](https://github.com/diminDDL/InfiCamPlus/issues/5) |
| UTi261M/UTi722M | `0bda:5830` | 🟥 | Shares a VID:PID with the P2 Pro but is not currently supported; PRs are welcome. | [#7](https://github.com/diminDDL/InfiCamPlus/issues/7) |
| HT820 | `0bda:5840` | ✅ | Reported working by users. | [#12](https://github.com/diminDDL/InfiCamPlus/issues/12) |
