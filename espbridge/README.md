# InfiCam ESP32-S3 USB bridge

This firmware exposes InfiCam Web Control to a USB-connected computer without
decoding or re-encoding the stream:

```
Android/InfiCam -- HTTP over WPA2 --> ESP32-S3 -- HTTP/HTTPS over USB-NCM --> PC
```

The fixed PC address is **http://192.168.7.1** by default, or
**https://192.168.7.1** when **Use encrypted HTTPS for connection** is enabled in
InfiCam. The phone joins
`InfiCamBridge` (WPA2 password hardcoded in esp src and Inficam App - `5KfHSF21`) and registers the active
InfiCam `WebViewServer` port and selected transport with the bridge. Optional HTTPS
is terminated on the ESP;
the decrypted HTTP requests, controls, MJPEG, state, images and video are forwarded
to the phone without decoding or re-encoding their content.

On the first build, `generate_https_certificate.sh` creates a local P-256 certificate
valid for the fixed IP `192.168.7.1` and `inficam.local`. The generated private key
and certificates are excluded from Git and reused by subsequent builds. The temporary
CA signing key is discarded after provisioning. Because public certificate authorities
cannot issue certificates for private IP addresses, install the generated
`inficam-bridge-ca.crt` as a trusted website authority on the USB-connected PC.
Firefox uses its own certificate store: open **Settings → Privacy & Security →
Certificates → View Certificates → Authorities → Import**, select that file and trust
it for identifying websites. Without installing the CA, the connection is still
encrypted after manually accepting the browser warning, but its identity is not
automatically verified.

Check the CA SHA-256 fingerprint before importing it:

```sh
openssl x509 -in inficam-bridge-ca.crt -noout -fingerprint -sha256
```

## Hardware

USB CDC-NCM uses the ESP32-S3 USB-OTG peripheral on GPIO 19 (D-) and GPIO 20
(D+). Use the board connector wired to USB-OTG. A connector wired only to the
USB Serial/JTAG peripheral can flash and log the board but cannot enumerate
the NCM interface.

## Build and flash

Run the script:

```sh
./flash_esp32s3.sh ~/path_to/esp-idf ~/path_to/idf-tools
```

The script displays the BOOT/RESET sequence, waits for `/dev/ttyACM*`, builds
and flashes the firmware, verifies the flash, waits for USB-NCM, and checks the
fixed HTTPS address. An explicit serial port can be supplied if several boards
are connected:

```sh
./flash_esp32s3.sh ~/path_to/esp-idf ~/path_to/idf-tools /dev/ttyACM1
```

For a manual ESP-IDF 6.0 or newer build:

```sh
./generate_https_certificate.sh
. "$IDF_PATH/export.sh"
idf.py set-target esp32s3
idf.py build
idf.py -p /dev/ttyACM0 flash
```

After boot, the USB-OTG port changes from the ROM/JTAG serial device to the NCM
network device, so `/dev/ttyACM0` disappearing is expected. To flash it again,
hold **BOOT**, tap **RESET**, release **BOOT**, and run the flash command while
the serial device is present.

On Linux, CDC-NCM should appear as a new Ethernet interface and DHCP should
assign the PC an address in `192.168.7.0/24`. In the Android app enable
**Settings → Use ESP-32 for connection**, accept Android's nearby Wi-Fi prompt
the first time, then start Web Control. The app displays the fixed URL only
after registration succeeds.

The two isolated subnets are:

- `192.168.8.0/24` — ESP SoftAP and Android phone;
- `192.168.7.0/24` — USB NCM, with ESP at `.1` and the PC normally at `.2`.

The USB DHCP server intentionally does not advertise a default Internet route.
An HTTP or HTTPS 503 response at the fixed address means the USB side is working but the
phone has not yet enabled Web Control or completed registration.

The old Rust hello-world skeleton is intentionally superseded by ESP-IDF C:
ESP-IDF provides the maintained TinyUSB CDC-NCM implementation for ESP32-S3,
which keeps the bridge small and avoids an unnecessary custom USB stack.
