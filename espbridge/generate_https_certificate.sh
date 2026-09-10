#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CA_CERT="$PROJECT_DIR/inficam-bridge-ca.crt"
SERVER_CERT="$PROJECT_DIR/main/server_cert.pem"
SERVER_KEY="$PROJECT_DIR/main/server_key.pem"

fail() {
	printf 'ERROR: %s\n' "$*" >&2
	exit 1
}

command -v openssl >/dev/null 2>&1 ||
	fail "OpenSSL is required to provision the HTTPS certificate"

existing=0
[[ -f "$CA_CERT" ]] && ((existing += 1))
[[ -f "$SERVER_CERT" ]] && ((existing += 1))
[[ -f "$SERVER_KEY" ]] && ((existing += 1))

if (( existing == 3 )); then
	openssl verify -CAfile "$CA_CERT" "$SERVER_CERT" >/dev/null ||
		fail "Existing bridge certificate is not signed by the local CA"
	openssl x509 -in "$SERVER_CERT" -noout -checkip 192.168.7.1 >/dev/null ||
		fail "Existing bridge certificate is not valid for 192.168.7.1"
	openssl x509 -in "$SERVER_CERT" -noout -checkend 2592000 >/dev/null ||
		fail "Bridge certificate expires within 30 days; rotate the local certificate"
	server_public="$(openssl x509 -in "$SERVER_CERT" -pubkey -noout |
		openssl pkey -pubin -outform DER 2>/dev/null | openssl sha256)"
	key_public="$(openssl pkey -in "$SERVER_KEY" -pubout -outform DER 2>/dev/null |
		openssl sha256)"
	[[ "$server_public" == "$key_public" ]] ||
		fail "Existing bridge certificate and private key do not match"
	chmod 0600 "$SERVER_KEY"
	printf 'Reusing the existing device-local HTTPS certificate.\n'
	exit 0
fi

(( existing == 0 )) ||
	fail "HTTPS provisioning is incomplete; keep or remove all three certificate files together"

temporary_dir="$(mktemp -d)"
trap 'rm -rf -- "$temporary_dir"' EXIT

openssl ecparam -name prime256v1 -genkey -noout \
	-out "$temporary_dir/ca.key"
openssl req -x509 -new -key "$temporary_dir/ca.key" -sha256 -days 7305 \
	-subj '/CN=InfiCam Bridge Local CA/O=InfiCamPlus' \
	-addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
	-addext 'keyUsage=critical,keyCertSign,cRLSign' \
	-out "$temporary_dir/ca.crt"

openssl ecparam -name prime256v1 -genkey -noout \
	-out "$temporary_dir/server.key"
openssl req -new -key "$temporary_dir/server.key" \
	-subj '/CN=192.168.7.1/O=InfiCamPlus' \
	-addext 'subjectAltName=IP:192.168.7.1,DNS:inficam.local' \
	-addext 'basicConstraints=critical,CA:FALSE' \
	-addext 'keyUsage=critical,digitalSignature' \
	-addext 'extendedKeyUsage=serverAuth' \
	-out "$temporary_dir/server.csr"
openssl x509 -req -in "$temporary_dir/server.csr" \
	-CA "$temporary_dir/ca.crt" -CAkey "$temporary_dir/ca.key" \
	-CAcreateserial -days 3650 -sha256 -copy_extensions copy \
	-out "$temporary_dir/server.crt"

openssl verify -CAfile "$temporary_dir/ca.crt" \
	"$temporary_dir/server.crt" >/dev/null
openssl x509 -in "$temporary_dir/server.crt" -noout \
	-checkip 192.168.7.1 >/dev/null

install -m 0644 "$temporary_dir/ca.crt" "$CA_CERT"
install -m 0644 "$temporary_dir/server.crt" "$SERVER_CERT"
install -m 0600 "$temporary_dir/server.key" "$SERVER_KEY"

printf '%s\n' \
	'Created a device-local HTTPS certificate for 192.168.7.1.' \
	"Install this CA certificate on the Web Control PC: $CA_CERT"
openssl x509 -in "$CA_CERT" -noout -fingerprint -sha256
