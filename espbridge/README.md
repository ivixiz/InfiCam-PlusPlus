# InfiCam ESP32-S3 USB bridge

This firmware exposes InfiCam Web Control to a USB-connected computer without
decoding or re-encoding the stream:

```
Android/InfiCam -- Wi-Fi SoftAP --> ESP32-S3 -- USB CDC-NCM --> PC
```

The fixed PC address is **http://192.168.7.1**. The phone joins
`InfiCamBridge` (WPA2 password hardcoded in esp src and Inficam App - `5KfHSF21`) and registers the active
InfiCam `WebViewServer` port with the bridge. HTTP requests, controls, MJPEG,
state, images and video are forwarded as unchanged TCP bytes.

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
fixed HTTP address. An explicit serial port can be supplied if several boards
are connected:

```sh
./flash_esp32s3.sh ~/path_to/esp-idf ~/path_to/idf-tools /dev/ttyACM1
```

For a manual ESP-IDF 6.0 or newer build:

```sh
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
An HTTP 503 response at the fixed address means the USB side is working but the
phone has not yet enabled Web Control or completed registration.

The old Rust hello-world skeleton is intentionally superseded by ESP-IDF C:
ESP-IDF provides the maintained TinyUSB CDC-NCM implementation for ESP32-S3,
which keeps the bridge small and avoids an unnecessary custom USB stack.
