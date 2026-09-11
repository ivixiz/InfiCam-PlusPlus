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

The available global series follow the selections in Measurement Settings. Point,
line and rectangle measurement objects add their own dynamically configurable
series. The chart uses
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

Chart sample rate, averaging and separate export are configured at the top of Chart
Properties rather than in the general Settings screen. The sample rate ranges from
`0.04 s` (the 25 FPS camera period) to `1800 s` (30 minutes). A newly opened chart
with no user-created measurement waits for the first point, line or rectangle before
starting its time axis at zero. Long sessions are bounded to 12,000 stored points;
older data is progressively decimated while preserving the overall trend and
elapsed-time scale.

<img width="2400" height="1080" alt="Screenshot_20260830-141904104" src="https://github.com/user-attachments/assets/55b56047-8df2-4c3c-97e3-dd452d3f2e79" />

### Spatial fixed-pattern autocalibration (deprecated)

The experimental single-temperature Spatial FPN autocalibration did not provide a
consistent accuracy improvement across cameras and is deprecated. It has been
removed from Settings and disconnected from frame processing, persistence and the
camera lifecycle. Old profiles are not loaded or applied. The former implementation
is retained as deprecated source documentation and its processing engine is not
compiled into the native library.

### Point, line and rectangle measurements

The Measurement Tools flyout contains Point, Line, Rectangle and Measurement
Settings. Select a tool, then tap the thermal image for a point or drag between two
positions for a line/rectangle. Coordinates are stored in normalized sensor space,
so measurements remain aligned through zoom, rotation, mirroring, export resolution
changes and cameras with different native resolutions.

- Points add numbered traces `Tp1` … `Tp5`.
- Lines add numbered pairs such as `Tlmin1` and `Tlmax1`.
- Rectangles add numbered groups such as `Trmin1`, `Trcen1` and `Trmax1`.

Every series appears in the compact, scrollable Chart Properties table with editable
name, line width, colour and visibility. Show and Delete are separate columns. Custom
rows have a trash action which permanently removes that recorded series; deleting an
object's last series also removes the corresponding thermogram measurement. The three
global series cannot be deleted. Hidden series are neither
displayed nor sampled. Tapping an existing object deletes the topmost/latest
overlapping object, while pressing and dragging translates it without changing its
shape; its chart history remains and
continues with a gap until the chart is deleted. Re-adding the same object type reuses
that slot and its trace names, colours and line widths instead of adding more legend
rows. Later slots receive distinct randomized colours. Up to five points, three lines
and three rectangles can be active at once. Web Control exposes the same tools,
objects, values and trace settings.

Starting a new Time Chart prunes historical custom legends which no longer have a
measurement on the thermogram. Long histories are rendered with per-pixel-column
line compression derived from ngspice's approach: first/last values and extrema are
preserved while redundant sub-pixel segments are skipped. Series bounds are maintained
incrementally, and the on-screen Android chart remains hardware accelerated.

### Optional ESP32-S3 USB Web Control bridge

**Settings → Use ESP-32 for connection** enables a dedicated local connection for
Web Control. The phone joins the `InfiCamBridge` access point and registers the
running Web Control server with the ESP32-S3. A PC connected to the ESP USB-OTG
port receives a CDC-NCM Ethernet interface and can then open the fixed address
**http://192.168.7.1** by default. The separate **Settings → Use encrypted HTTPS
for connection** option changes both direct LAN Web Control and the ESP endpoint to
HTTPS. With HTTPS enabled, TLS terminates on the ESP32-S3; behind it, the bridge
forwards the existing controls, state, MJPEG and export downloads to the phone
without decoding or re-encoding camera data. Install the local bridge CA certificate
from `espbridge/inficam-bridge-ca.crt` on the PC to avoid the browser's private-CA
warning. HTTP remains available for managed browsers which do not allow installing
or trusting that CA.

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
- InfiCam is also an Android share target for photos, documents and arbitrary files.
  When Web Control is active, shared content is staged without loading it into memory
  and downloaded automatically by the connected browser over the existing LAN or
  ESP32 connection. Temporary copies are removed after a successful transfer.
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
control without requiring a cloud service. Direct LAN access and the optional
ESP32-S3 address use HTTP by default for compatibility with managed browsers. Enable
**Use encrypted HTTPS for connection** to encrypt either route. The direct HTTPS
identity is generated once in Android Keystore, where its private EC key remains
non-exportable.

Available synchronized controls include:

- Time Chart start, pause, resume, and long-press delete;
- palette selection and a dual-thumb locked palette range;
- mirror and shutter calibration;
- application Settings;
- point, line and rectangle Measurement Tools plus Measurement Settings;
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
When HTTPS is enabled, a public certificate authority cannot validate a changing
private LAN IP, so the browser shows a certificate warning on the first direct
connection. Verify that the displayed IP belongs to the phone and accept the local
certificate exception; the same Android Keystore identity is reused until the app
data is cleared. HTTP avoids certificate requirements but does not encrypt traffic;
use it only on a trusted local or isolated ESP USB network.

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
