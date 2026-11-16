#!/bin/bash
# Entry point for the Android engine measurement (docs/GUEST_PROFILE.md "The engine, measured",
# "On a phone"). The tools live in keliver-compose-gatea-profile/engine/.
#   scripts/engine-android.sh build <work dir> <out dir>        cross-compile qjs (build-engines-android.sh)
#   scripts/engine-android.sh device [-s SERIAL] <command> ...  run it on a device (run-device.sh)
set -euo pipefail
engine="$(cd "$(dirname "$0")/.." && pwd)/keliver-compose-gatea-profile/engine"
what="${1:?usage: engine-android.sh build <work dir> <out dir> | device [-s SERIAL] <command> ...}"; shift
case "$what" in
  build) exec bash "$engine/build-engines-android.sh" "$@" ;;
  device) exec bash "$engine/run-device.sh" "$@" ;;
  *) echo "unknown: $what (build or device)" >&2; exit 2 ;;
esac
