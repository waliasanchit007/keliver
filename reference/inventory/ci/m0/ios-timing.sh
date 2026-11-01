# Sourced by ci/ios.sh after W5: M0's timing marks on iOS, against W3's static
# HTTPS server (its site is at v9). Uses ios.sh's APP, UDID, BID, APPB, WORK,
# EV, HERE, launch, ok/bad, and W3. Only a host from templates with the marks
# (src/timing/); an older one SKIPs.
#   T1  the Debug build logs open, code, first-frame and content (the guest's
#       KeliverTiming.mark), each once, on the console and in
#       Library/Caches/keliver/timing.log; first-frame and content not before code
#   T2  the Benchmark configuration (Release settings) loads v9 and logs the
#       same marks; the Release configuration's binary has none of them
#   T3  M4's guest frame profile, in both: in T1's and T2's launch, Add 1
#       twice on Espresso beans (m0/ios-tap, an XCTest UI test that attaches
#       to the running host), so the guest recomposes in a frame — a launch
#       alone times only an empty frame; then well-formed "frames" lines on the
#       console and in the file, with a timed guest frame that sent a change
#       list and an applied change list (frames.py --changed, as on Android); a
#       host from templates without it SKIPs. The test runner's start sends the
#       host to the background and back: the Debug build then checks for an
#       update, harmless while the server stays at v9 (a newer bundle would
#       load and repeat T1's marks). If the driver does not build, that is one
#       FAIL and T3 reads frames.py without --changed, as before the taps
#   T4  M5's memory profile, in both, in a launch of its own (its extra screens
#       would break T1's "once"): after the first screen's content, two memory
#       cycles (the Darwin notification <bundle id>.keliver.memory-cycle opens a
#       second screen, closes it after its content and samples "closed"); then
#       the points in order with QuickJS read from the runtime on (memory.py).
#       It prints the growth from cycle 1's close to cycle 2's and sets no bound
#       on it yet: the simulator's numbers come first. A host from templates
#       without it SKIPs
if grep -rqs "interface KeliverTrace" "$APP/host-ios/src/timing"; then
  echo "--- M0: timing marks"
  python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/m0-server.log" &
  SPID=$!
  for _ in $(seq 1 30); do curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done
  m0_at(){ sed -n "s/.*KeliverTiming: $2 \([0-9][0-9]*\)\r*$/\1/p" "$1" | head -1; }
  m0_count(){ grep -c "KeliverTiming: $2 [0-9]" "$1"; }
  # T3's tap driver, built once (it has no app of its own; it drives the host by bundle id).
  M0_TAP=""; M0_TAP_RC=1
  if grep -rqs "class FrameWindow" "$APP/host-ios/src/timing"; then
    xcodebuild build-for-testing -project "$HERE/m0/ios-tap/IosTap.xcodeproj" -scheme IosTap \
        -destination "id=$UDID" -derivedDataPath "$WORK/dd-tap" > "$EV/m0-ios-tap-build.log" 2>&1 \
      && M0_TAP=1 || bad "T3: the tap driver (m0/ios-tap) did not build: $(grep -E 'error:' "$EV/m0-ios-tap-build.log" | head -2 | tr '\n' ' ')"
  fi
  # One launch, one console, no relaunch: ios.sh's launch() relaunches without a
  # console when the first screenshot is blank, which would lose the marks.
  # With "tap", T3's Add 1 twice runs in the launch (log: $1-tap.log); M0_TAP_RC is its status.
  m0_launch(){  # $1 label, [$2 tap]
    local pid
    xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
    xcrun simctl launch --console-pty "$UDID" "$BID" > "$EV/$1.console.txt" 2>&1 &
    pid=$!; sleep 30
    if [ "${2:-}" = tap ]; then
      M0_TAP_RC=1
      if [ -n "$M0_TAP" ]; then
        local tpid t=0
        rm -rf "$EV/$1-tap.xcresult"
        TEST_RUNNER_TAP_BUNDLE_ID="$BID" xcodebuild test-without-building -project "$HERE/m0/ios-tap/IosTap.xcodeproj" \
          -scheme IosTap -destination "id=$UDID" -derivedDataPath "$WORK/dd-tap" \
          -resultBundlePath "$EV/$1-tap.xcresult" > "$EV/$1-tap.log" 2>&1 &
        tpid=$!
        # No timeout(1) on macOS: a hung runner fails this row, not the job.
        while kill -0 "$tpid" 2>/dev/null && [ "$t" -lt 300 ]; do sleep 5; t=$((t + 5)); done
        local stopped=""
        if kill -0 "$tpid" 2>/dev/null; then
          kill "$tpid" 2>/dev/null; stopped=1
          while kill -0 "$tpid" 2>/dev/null && [ "$t" -lt 330 ]; do sleep 5; t=$((t + 5)); done
          kill -9 "$tpid" 2>/dev/null
        fi
        wait "$tpid"; M0_TAP_RC=$?
        [ -z "$stopped" ] || echo "ios-timing: the tap driver was stopped after 300 s" >> "$EV/$1-tap.log"
        # The test, not just xcodebuild, must have passed.
        grep -q "Test Case '-\[IosTapUITests.AddOneTwice testAddOneTwice\]' passed" "$EV/$1-tap.log" || M0_TAP_RC=1
      fi
    fi
    kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  }
  m0_marks(){  # $1 label, $2 console file
    local f="$2" o c ff ct n
    for n in open code first-frame content; do
      [ "$(m0_count "$f" "$n")" = 1 ] || { bad "$1: '$n' is logged $(m0_count "$f" "$n") times, not once"; return 1; }
    done
    o="$(m0_at "$f" open)"; c="$(m0_at "$f" code)"; ff="$(m0_at "$f" first-frame)"; ct="$(m0_at "$f" content)"
    if [ "$ff" -ge "$c" ] && [ "$ct" -ge "$c" ]; then
      ok "$1: open, code, first-frame, content: code $(( (c - o) / 1000000 )) ms after open, first-frame +$(( (ff - c) / 1000000 )) ms and content +$(( (ct - c) / 1000000 )) ms after code"
    else
      bad "$1: the marks are out of order: open $o code $c first-frame $ff content $ct"; return 1
    fi
  }
  m0_frames(){  # $1 label, $2 console file, $3 the launch's label (its tap log)
    local out
    if ! grep -rqs "class FrameWindow" "$APP/host-ios/src/timing"; then
      echo "  SKIP  $1: this host predates the guest frame profile (not counted)"; return 0
    fi
    if [ -z "$M0_TAP" ]; then  # its failed build is counted once, above
      echo "  NOTE  $1: no tap driver, so only well-formed lines are checked"
      out="$(python3 "$HERE/m0/frames.py" "$2")" && ok "$1: the guest frame profile: $out" || bad "$1: the guest frame profile: $out"
      return 0
    fi
    if [ "$M0_TAP_RC" != 0 ]; then
      bad "$1: Add 1 twice on an open item (no profile read): $(grep -E 'error: |AddOneTwice: |ios-timing: ' "$EV/$3-tap.log" | head -3 | cut -c1-200 | tr '\n' ' ')"
      return 0
    fi
    ok "$1: Add 1 twice on an open item ($(grep -c 'AddOneTwice: F[12] ' "$EV/$3-tap.log") steps, $3-tap.log)"
    out="$(python3 "$HERE/m0/frames.py" --changed "$2")" && ok "$1: the guest frame profile: $out" || bad "$1: the guest frame profile: $out"
  }

  m0_memory(){  # $1 label: a launch, two memory cycles, then the profile
    local pid out
    if ! grep -rqs "keliver.memory-cycle" "$APP/host-ios/src/timing"; then
      echo "  SKIP  $1: this host predates the memory profile (not counted)"; return 0
    fi
    xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
    xcrun simctl launch --console-pty "$UDID" "$BID" > "$EV/$1.console.txt" 2>&1 &
    pid=$!; sleep 30
    xcrun simctl spawn "$UDID" notifyutil -p "$BID.keliver.memory-cycle"; sleep 25
    xcrun simctl spawn "$UDID" notifyutil -p "$BID.keliver.memory-cycle"; sleep 25
    kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
    out="$(python3 "$HERE/m0/memory.py" "$EV/$1.console.txt")" && ok "$1: the memory profile: $out" || bad "$1: the memory profile: $out"
  }

  # T1: the Debug build W5 left installed (its server is https://localhost:8443).
  m0_launch T1 tap
  C="$EV/T1.console.txt"
  m0_marks T1 "$C"
  grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$C" && ok "T1: the Debug build ran v9" || bad "T1: v9 did not load"
  TL="$(xcrun simctl get_app_container "$UDID" "$BID" data)/Library/Caches/keliver/timing.log"
  ct="$(m0_at "$C" content)"
  [ -n "$ct" ] && grep -qx "content $ct" "$TL" 2>/dev/null \
    && ok "T1: Library/Caches/keliver/timing.log has the same content mark ($ct)" || bad "T1: the file sink: $(tail -3 "$TL" 2>&1 | tr '\n' ' ')"
  m0_frames T3 "$C" T1
  if grep -rqs "class FrameWindow" "$APP/host-ios/src/timing"; then
    # This launch's own line: the file outlives the app's earlier runs.
    fl="$(LC_ALL=C tr -d '\r' < "$C" | LC_ALL=C sed -n 's/.*KeliverTiming: \(frames [0-9][0-9]* n=.*\)$/\1/p' | head -1)"
    [ -n "$fl" ] && grep -qxF "$fl" "$TL" 2>/dev/null \
      && ok "T3: Library/Caches/keliver/timing.log has this launch's frames line" || bad "T3: the file sink lacks this launch's frames line: ${fl:-none on the console}"
  fi
  m0_memory T4

  # T2: the Benchmark configuration. A Release-type build refuses an Info.plist
  # ATS exception (the scaffolder wrote one for the relay's http://), so it is
  # set aside for these builds and put back.
  PLIST="$APP/host-ios/iosApp/Info.plist"; cp "$PLIST" "$WORK/m0-info.plist.saved"
  /usr/libexec/PlistBuddy -c "Delete :NSAppTransportSecurity" "$PLIST" > /dev/null 2>&1 || true
  m0_build(){  # $1 configuration
    ( cd "$APP" && xcodebuild -project host-ios/iosApp.xcodeproj -scheme iosApp -configuration "$1" \
        -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$WORK/dd-m0" \
        CODE_SIGNING_ALLOWED=NO build ) > "$EV/m0-xcodebuild-$1.log" 2>&1
  }
  if m0_build Benchmark; then
    ok "T2: the Benchmark configuration built (a release Kotlin framework)"
    BAPP="$(find "$WORK/dd-m0/Build/Products/Benchmark-iphonesimulator" -maxdepth 1 -name '*.app' | head -1)"
    # Installed over the Debug build: the app's data (cache, rollback floor) stays for W8.
    xcrun simctl install "$UDID" "$BAPP"
    m0_launch T2 tap
    C="$EV/T2.console.txt"
    grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$C" && ok "T2: the Benchmark build verified and ran v9" \
      || bad "T2: v9 did not load: $(grep -E 'KeliverHost|codeLoad' "$C" | head -3 | tr '\n' ' ')"
    m0_marks T2 "$C"
    m0_frames "T3 (Benchmark)" "$C" T2
    # The positive control for the Release check below: these names are in a build that has them.
    m0_has_all=1
    for m0_n in KeliverTrace FrameWindow KeliverMemory; do
      find "$BAPP" -type f -perm -u+x -exec grep -aqE "$m0_n" {} \; -print | grep -q . || m0_has_all=0
    done
    [ "$m0_has_all" = 1 ] \
      && ok "T2: the Benchmark build's binaries carry KeliverTrace, FrameWindow and KeliverMemory (the Release check can see them)" \
      || bad "T2: the Benchmark build's binaries lack a timing class name: the Release check would prove nothing"
    m0_memory "T4-Benchmark"
  else
    bad "T2: the Benchmark build failed"; grep -E '^e: |error:' "$EV/m0-xcodebuild-Benchmark.log" | head
  fi
  # The store configuration has none of it: its binary names no KeliverTrace.
  if m0_build Release; then
    RAPP="$(find "$WORK/dd-m0/Build/Products/Release-iphonesimulator" -maxdepth 1 -name '*.app' | head -1)"
    # Class names, which the binary keeps in ASCII (Kotlin string constants are UTF-16).
    ! find "$RAPP" -type f -perm -u+x -exec grep -aqE 'KeliverTrace|FrameWindow|KeliverMemory' {} \; -print | grep -q . \
      && ok "T2: the Release build's binaries carry no timing code (no KeliverTrace, FrameWindow or KeliverMemory)" || bad "T2: the Release build carries timing code"
  else
    bad "T2: the Release build failed"; grep -E '^e: |error:' "$EV/m0-xcodebuild-Release.log" | head
  fi
  cp "$WORK/m0-info.plist.saved" "$PLIST"
  # Back to the Debug build for the rows after this one, its data kept.
  xcrun simctl install "$UDID" "$APPB"
  kill "$SPID" 2>/dev/null; wait "$SPID" 2>/dev/null; SPID=""
else
  echo "  SKIP  T1-T2: this host predates the timing marks (not counted)"
fi
