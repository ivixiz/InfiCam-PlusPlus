#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
    printf 'Usage: %s <IDF_PATH> <IDF_TOOLS_PATH> [SERIAL_PORT]\n' "$0" >&2
    printf '\nExample:\n' >&2
    printf '  %s ~/esp/esp-idf ~/.espressif /dev/ttyACM0\n' "$0" >&2
    exit 2
}

[[ $# -ge 2 && $# -le 3 ]] || usage

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

IDF_PATH="$(realpath -m -- "$1")"
IDF_TOOLS_PATH="$(realpath -m -- "$2")"
SERIAL_PORT="${3:-}"

BOOT_TIMEOUT_SECONDS=60
NCM_TIMEOUT_SECONDS=45

fail() {
	printf '\nERROR: %s\n' "$*" >&2
	exit 1
}

find_serial_port() {
	local port
	if [[ -n "$SERIAL_PORT" ]]; then
		if [[ -e "$SERIAL_PORT" ]]; then
			printf '%s\n' "$SERIAL_PORT"
			return 0
		fi
		return 1
	fi
	for port in /dev/ttyACM*; do
		if [[ -e "$port" ]]; then
			printf '%s\n' "$port"
			return 0
		fi
	done
	return 1
}

wait_for_serial_port() {
	local elapsed=0
	local detected
	while (( elapsed < BOOT_TIMEOUT_SECONDS * 4 )); do
		if detected="$(find_serial_port)"; then
			printf '%s\n' "$detected"
			return 0
		fi
		sleep 0.25
		((elapsed += 1))
	done
	return 1
}

ncm_present() {
	command -v lsusb >/dev/null 2>&1 && lsusb -d 303a:4000 2>/dev/null | grep -q .
}

wait_for_ncm() {
	local timeout_seconds="${1:-$NCM_TIMEOUT_SECONDS}"
	local elapsed=0
	while (( elapsed < timeout_seconds * 2 )); do
		if ncm_present; then
			return 0
		fi
		sleep 0.5
		((elapsed += 1))
	done
	return 1
}

[[ -f "$IDF_PATH/export.sh" ]] ||
	fail "ESP-IDF not found at $IDF_PATH"
[[ -d "$IDF_TOOLS_PATH" ]] ||
	fail "ESP-IDF tools not found at $IDF_TOOLS_PATH"

printf '%s\n' \
	'InfiCam ESP32-S3 programmer' \
	'============================' \
	"Project:   $PROJECT_DIR" \
	"ESP-IDF:   $IDF_PATH" \
	"IDF tools: $IDF_TOOLS_PATH" \
	'' \
	'Put the board into the ROM bootloader now:' \
	'  1. Hold BOOT.' \
	'  2. Press and release RESET.' \
	'  3. Release BOOT.' \
	'' \
	"Waiting up to $BOOT_TIMEOUT_SECONDS seconds for /dev/ttyACM* ..."

SERIAL_PORT="$(wait_for_serial_port)" ||
	fail "ESP32-S3 bootloader did not appear. Repeat BOOT + RESET."
printf 'Found ESP32-S3 bootloader: %s\n\n' "$SERIAL_PORT"

export IDF_PATH IDF_TOOLS_PATH
# ESP-IDF's environment script is not guaranteed to be nounset-clean.
set +u
# shellcheck source=/dev/null
source "$IDF_PATH/export.sh"
set -u

cd "$PROJECT_DIR"
if [[ -f build/CMakeCache.txt ]] &&
	! grep -Fq "$IDF_PATH" build/CMakeCache.txt; then
	printf '%s\n' \
		'' \
		'ESP-IDF was moved since the previous build; refreshing generated build files...'
	idf.py fullclean
fi
if [[ ! -f sdkconfig ]] || ! grep -q '^CONFIG_IDF_TARGET="esp32s3"$' sdkconfig; then
	idf.py set-target esp32s3
fi

printf '\nBuilding and flashing firmware...\n'
idf.py -p "$SERIAL_PORT" flash

printf '\nFlash verification succeeded. Waiting for the USB-NCM device...\n'
if ! wait_for_ncm 5; then
	printf '%s\n' \
		'' \
		'The board is still in download mode.' \
		'Press RESET once now (do not press BOOT).'
	wait_for_ncm "$NCM_TIMEOUT_SECONDS" ||
		fail "USB-NCM did not appear after flashing/reset."
fi

printf 'USB-NCM device detected. Waiting for http://192.168.7.1 ...\n'
HTTP_CODE="000"
for _ in $(seq 1 20); do
	HTTP_CODE="$(curl --silent --output /dev/null --write-out '%{http_code}' \
		--max-time 2 http://192.168.7.1/ 2>/dev/null || true)"
	if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "503" ]]; then
		break
	fi
	sleep 1
done

if [[ "$HTTP_CODE" == "200" ]]; then
	printf '%s\n' \
		'Programming complete: Web Control is connected.' \
		'Open http://192.168.7.1'
elif [[ "$HTTP_CODE" == "503" ]]; then
	printf '%s\n' \
		'Programming complete: the bridge is ready and waiting for the phone.' \
		'Connect the phone to InfiCam-Bridge and enable Web View.' \
		'Then open http://192.168.7.1'
else
	printf '%s\n' \
		'Programming complete and USB-NCM is present.' \
		'The PC has not obtained its USB network address yet; it may need a few more seconds.'
fi
