# Sourced by ci/ios.sh after W5: M0's timing marks on iOS, against W3's static
# HTTPS server (its site is at v9). Uses ios.sh's APP, UDID, BID, APPB, WORK,
# EV, HERE, launch, ok/bad, and W3. Only a host from templates with the marks
# (src/timing/); an older one SKIPs.
#   T1  the Debug build logs open, code, first-frame and content (the guest's
#       KeliverTiming.mark), each once, on the console and in
#       Library/Caches/keliver/timing.log; first-frame and content not before code
#   T2  the Benchmark configuration (Release settings) loads v9 and logs the
#       same marks; the Release configuration's binary has none of them
#   T3  M4's guest frame profile, in both: well-formed "frames" lines on the
#       console and in the file, with a timed guest frame and an applied change
#       list (frames.py); a host from templates without it SKIPs. There is no
#       tap step: after a launch the simulator times only an empty frame, so a
#       PASS shows well-formed lines, not a timed frame that sent changes
#       (Android's T3 checks that: frames.py --changed)
if grep -rqs "interface KeliverTrace" "$APP/host-ios/src/timing"; then
  echo "--- M0: timing marks"
  python3 "$HERE/w3/static_https.py" "$W3/site" 8443 "$W3/tls/server.pem" "$W3/tls/server.key" "$EV/m0-server.log" &
  SPID=$!
  for _ in $(seq 1 30); do curl -sf --cacert "$W3/tls/ca.pem" -m 2 -o /dev/null https://localhost:8443/bundles/index.json && break; sleep 1; done
  m0_at(){ sed -n "s/.*KeliverTiming: $2 \([0-9][0-9]*\)\r*$/\1/p" "$1" | head -1; }
  m0_count(){ grep -c "KeliverTiming: $2 [0-9]" "$1"; }
  # One launch, one console, no relaunch: ios.sh's launch() relaunches without a
  # console when the first screenshot is blank, which would lose the marks.
  m0_launch(){  # $1 label
    local pid
    xcrun simctl terminate "$UDID" "$BID" > /dev/null 2>&1
    xcrun simctl launch --console-pty "$UDID" "$BID" > "$EV/$1.console.txt" 2>&1 &
    pid=$!; sleep 30; kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
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
  m0_frames(){  # $1 label, $2 console file
    local out
    if ! grep -rqs "class FrameWindow" "$APP/host-ios/src/timing"; then
      echo "  SKIP  $1: this host predates the guest frame profile (not counted)"; return 0
    fi
    out="$(python3 "$HERE/m0/frames.py" "$2")" && ok "$1: the guest frame profile: $out" || bad "$1: the guest frame profile: $out"
  }

  # T1: the Debug build W5 left installed (its server is https://localhost:8443).
  m0_launch T1
  C="$EV/T1.console.txt"
  m0_marks T1 "$C"
  grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$C" && ok "T1: the Debug build ran v9" || bad "T1: v9 did not load"
  TL="$(xcrun simctl get_app_container "$UDID" "$BID" data)/Library/Caches/keliver/timing.log"
  ct="$(m0_at "$C" content)"
  [ -n "$ct" ] && grep -qx "content $ct" "$TL" 2>/dev/null \
    && ok "T1: Library/Caches/keliver/timing.log has the same content mark ($ct)" || bad "T1: the file sink: $(tail -3 "$TL" 2>&1 | tr '\n' ' ')"
  m0_frames T3 "$C"
  if grep -rqs "class FrameWindow" "$APP/host-ios/src/timing"; then
    # This launch's own line: the file outlives the app's earlier runs.
    fl="$(LC_ALL=C tr -d '\r' < "$C" | LC_ALL=C sed -n 's/.*KeliverTiming: \(frames [0-9][0-9]* n=.*\)$/\1/p' | head -1)"
    [ -n "$fl" ] && grep -qxF "$fl" "$TL" 2>/dev/null \
      && ok "T3: Library/Caches/keliver/timing.log has this launch's frames line" || bad "T3: the file sink lacks this launch's frames line: ${fl:-none on the console}"
  fi

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
    m0_launch T2
    C="$EV/T2.console.txt"
    grep -q "codeLoadSuccess modules=[0-9]* sequence=9" "$C" && ok "T2: the Benchmark build verified and ran v9" \
      || bad "T2: v9 did not load: $(grep -E 'KeliverHost|codeLoad' "$C" | head -3 | tr '\n' ' ')"
    m0_marks T2 "$C"
    m0_frames "T3 (Benchmark)" "$C"
  else
    bad "T2: the Benchmark build failed"; grep -E '^e: |error:' "$EV/m0-xcodebuild-Benchmark.log" | head
  fi
  # The store configuration has none of it: its binary names no KeliverTrace.
  if m0_build Release; then
    RAPP="$(find "$WORK/dd-m0/Build/Products/Release-iphonesimulator" -maxdepth 1 -name '*.app' | head -1)"
    ! find "$RAPP" -type f -perm -u+x -exec grep -aqE 'KeliverTrace|FrameWindow' {} \; -print | grep -q . \
      && ok "T2: the Release build's binaries carry no timing code (no KeliverTrace, no FrameWindow)" || bad "T2: the Release build carries timing code"
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
