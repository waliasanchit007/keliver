#!/usr/bin/env bash
#
# W7, the physical-device route only (KELIVER_DEVICE=physical in prepare.sh):
# make ONE build type of a test APK trust the harness's throwaway CA, inside the
# APK, because a stock phone's system store cannot take it (ci/w3/android-ca.sh
# needs `adb root`, which only emulator images allow).
#
#   ci/w7/android-test-ca.sh <source-set-dir> <ca.pem>
#   e.g. host-android/src/debug, or app/src/release for the embed fixture's R8 build
#
# Writes into that source set, and nowhere else:
#   res/raw/keliver_test_ca.pem
#   res/xml/keliver_test_network.xml   system CAs + this CA; no cleartext
#   AndroidManifest.xml                points the application at that file
# Only harness work copies get this (the scaffolded host-android/ and the copy
# of reference/embed/android under the work dir); no template or fixture in
# the repository changes. An APK built this way trusts a CA that exists only in
# this run's work dir (tls.sh deletes the CA's private key once the server
# certificate is signed), so it is a test build: never ship one.
set -euo pipefail
SET="${1:?usage: $0 <source-set-dir> <ca.pem>}"
CA="${2:?}"
[ -f "$CA" ] || { echo "no CA at $CA" >&2; exit 1; }
MERGE=false
if [ -e "$SET/AndroidManifest.xml" ]; then
  # A host scaffolded with the debug link (ED3) has its own src/debug manifest, whose
  # <application> carries no attributes: give it this one. Any other manifest is not touched.
  [ "$(grep -c '<application>' "$SET/AndroidManifest.xml")" = 1 ] && grep -q 'KeliverDebugLinkActivity' "$SET/AndroidManifest.xml" \
    && ! grep -q 'networkSecurityConfig' "$SET/AndroidManifest.xml" \
    || { echo "$SET/AndroidManifest.xml exists; not overwriting it" >&2; exit 1; }
  MERGE=true
fi
mkdir -p "$SET/res/raw" "$SET/res/xml"
cp "$CA" "$SET/res/raw/keliver_test_ca.pem"
cat > "$SET/res/xml/keliver_test_network.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<!-- Written by reference/inventory/ci/w7/android-test-ca.sh: a TEST build for a
     physical device. Trusts the system CAs and the harness's throwaway CA. -->
<network-security-config>
  <base-config cleartextTrafficPermitted="false">
    <trust-anchors>
      <certificates src="system" />
      <certificates src="@raw/keliver_test_ca" />
    </trust-anchors>
  </base-config>
</network-security-config>
XML
if $MERGE; then
  sed -i.bak 's|<application>|<application android:networkSecurityConfig="@xml/keliver_test_network">|' "$SET/AndroidManifest.xml"
  rm -f "$SET/AndroidManifest.xml.bak"
else
  cat > "$SET/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:networkSecurityConfig="@xml/keliver_test_network" />
</manifest>
XML
fi
echo "test CA trusted in $SET (res/raw/keliver_test_ca.pem, res/xml/keliver_test_network.xml)"
