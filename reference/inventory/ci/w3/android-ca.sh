#!/usr/bin/env bash
#
# Trust a CI test CA on THIS emulator, system-wide, until it reboots: the
# Android 7–13 way, a tmpfs over /system/etc/security/cacerts holding the
# image's own CAs plus this one. Needs `adb root` (emulator images that allow
# it). Nothing on the image is changed; a reboot drops it.
#
#   ci/w3/android-ca.sh <serial> <ca.pem>
set -euo pipefail
SERIAL="${1:?usage: $0 <serial> <ca.pem>}"
CA="${2:?}"
a(){ adb -s "$SERIAL" "$@"; }
H="$(openssl x509 -subject_hash_old -noout -in "$CA")"
a root > /dev/null 2>&1 || true
a wait-for-device
for _ in $(seq 1 30); do [ "$(a shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && break; sleep 1; done
[ "$(a shell id -u | tr -d '\r')" = 0 ] || { echo "adb root is not available on this image" >&2; exit 1; }
a push "$CA" "/data/local/tmp/$H.0" > /dev/null
a shell "set -e
D=/system/etc/security/cacerts
T=/data/local/tmp/keliver-cacerts
rm -rf \$T; mkdir -p \$T
cp \$D/* \$T/
cp /data/local/tmp/$H.0 \$T/
mount -t tmpfs tmpfs \$D
cp \$T/* \$D/
chown root:root \$D/*
chmod 644 \$D/*
chcon u:object_r:system_file:s0 \$D/*"
a shell ls "/system/etc/security/cacerts/$H.0"
