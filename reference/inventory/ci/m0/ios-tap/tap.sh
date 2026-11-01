# Sourced by ci/ios.sh: the iOS tap driver, AddOne.swift (an XCTest UI test with no app of
# its own, which attaches to the running host by bundle id). Uses ios.sh's UDID, BID, WORK,
# EV and HERE. For ios.sh's P3 and ci/m0/ios-timing.sh's T3.
#   tap_build        builds it once ($EV/ios-tap-build.log); TAP_BUILT=1 when it built
#   tap_run L N [F]  in the running host, Add 1 N times on Espresso beans, each observed,
#                    and first that it opens with F on hand, if given; log $EV/L-tap.log,
#                    result bundle $EV/L-tap.xcresult. 0 only if the test case passed
#   tap_steps L      the steps L's log shows (F1 the item opened, F2… the taps)
#   tap_why L        L's first failure lines, for a FAIL
TAP_BUILT=""
tap_build(){
  xcodebuild build-for-testing -project "$HERE/m0/ios-tap/IosTap.xcodeproj" -scheme IosTap \
    -destination "id=$UDID" -derivedDataPath "$WORK/dd-tap" > "$EV/ios-tap-build.log" 2>&1 && TAP_BUILT=1
}
tap_run(){
  local label="$1" count="$2" from="${3:-}" tpid t=0 stopped="" rc
  [ -n "$TAP_BUILT" ] || return 1
  rm -rf "$EV/$label-tap.xcresult"   # xcodebuild refuses an existing result bundle
  TEST_RUNNER_TAP_BUNDLE_ID="$BID" TEST_RUNNER_TAP_COUNT="$count" TEST_RUNNER_TAP_FROM="$from" \
    xcodebuild test-without-building -project "$HERE/m0/ios-tap/IosTap.xcodeproj" -scheme IosTap \
      -destination "id=$UDID" -derivedDataPath "$WORK/dd-tap" \
      -resultBundlePath "$EV/$label-tap.xcresult" > "$EV/$label-tap.log" 2>&1 &
  tpid=$!
  # No timeout(1) on macOS: a hung runner fails this row, not the job.
  while kill -0 "$tpid" 2>/dev/null && [ "$t" -lt 300 ]; do sleep 5; t=$((t + 5)); done
  if kill -0 "$tpid" 2>/dev/null; then
    kill "$tpid" 2>/dev/null; stopped=1
    while kill -0 "$tpid" 2>/dev/null && [ "$t" -lt 330 ]; do sleep 5; t=$((t + 5)); done
    kill -9 "$tpid" 2>/dev/null
  fi
  wait "$tpid"; rc=$?
  [ -z "$stopped" ] || echo "ios-tap: the tap driver was stopped after 300 s" >> "$EV/$label-tap.log"
  # The test, not just xcodebuild, must have passed.
  grep -q "Test Case '-\[IosTapUITests.AddOne testAddOne\]' passed" "$EV/$label-tap.log" || rc=1
  return "$rc"
}
tap_steps(){ grep -c 'AddOne: F[0-9]* ' "$EV/$1-tap.log"; }
tap_why(){ grep -E 'error: |AddOne: |ios-tap: ' "$EV/$1-tap.log" | head -3 | cut -c1-200 | tr '\n' ' '; }
