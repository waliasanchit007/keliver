# 0.5.0 A12 reference (2026-11-26) phone library: checkpoint 2's lib.sh (series G-J's) with its own
# directory (sourced by master.sh, h2.sh and k.sh; runs ON the phone under nohup sh). Push this
# directory to /data/local/tmp/ref1126/, with the benchmarks APK's trace_processor_shell beside it.
# install() and taps() are not used on 2026-11-26: the app was already installed (NF5 R0's install:
# `install -r -d`, profileinstaller's broadcast, speed-profile). Order: master.sh (G, H), then h2.sh
# (the retained back, then k.sh), then an.sh for the medians (THREE_WAY_BENCHMARK.md, "A12
# reference for 0.5.0, 2026-11-26").
D=/data/local/tmp/ref1126
PKG=dev.keliver.sample
ACT=$PKG/dev.keliver.sample.host.threeway.ThreewayActivity
F=/sdcard/Android/data/$PKG/files/threeway-results.txt
OUT=$D/out
LOG=$OUT/run.log
mkdir -p $OUT

cpuidle() {
  a=$(head -1 /proc/stat); sleep 2; b=$(head -1 /proc/stat)
  echo "$a|$b" | awk -F'|' '{split($1,x," ");split($2,y," ");t=0;for(i=2;i<=9;i++)t+=y[i]-x[i]; printf "%d", 100*(y[5]-x[5])/t}'
}

state() {
  bat=$(dumpsys battery | awk '/level:/{l=$2}/AC powered/{a=$3}/USB powered/{u=$3}/temperature/{t=$2}END{printf "level=%s ac=%s usb=%s temp=%s",l,a,u,t/10}')
  th=$(dumpsys thermalservice | awk -F': ' '/Thermal Status/{print $2; exit}')
  echo "STATE $1 $(date +%H:%M:%S) up=$(cut -d' ' -f1 /proc/uptime) load=$(cut -d' ' -f1-3 /proc/loadavg) $bat thermal=$th idle=$(cpuidle)" >> $LOG
}

batteryok() {
  dumpsys battery | awk '/level:/{l=$2}/AC powered/{a=$3}/USB powered/{u=$3}END{exit !(l>=30 || a=="true" || u=="true")}'
}

# install <apk on phone> <label>: replace the sample, run it once (profileinstaller), compile speed-profile.
install() {
  echo "INSTALL $2 $(date +%H:%M:%S) $(pm install -r "$1" 2>&1 | tr '\n' ' ')" >> $LOG
  input keyevent KEYCODE_WAKEUP
  am start -S -n $ACT --ez overLockScreen true --ez localApi true -e form guest >/dev/null 2>&1
  sleep 12
  am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE $PKG/androidx.profileinstaller.ProfileInstallReceiver >/dev/null 2>&1
  sleep 3
  am force-stop $PKG
  echo "COMPILE $2 $(cmd package compile -f -m speed-profile $PKG 2>&1 | tr '\n' ' ')" >> $LOG
  echo "BUILD $2 $(dumpsys package $PKG | grep -E 'lastUpdateTime' | head -1)" >> $LOG
  sleep 5
}

# bench <label> <timeout s> <am extras...>: one fresh process, wait for {"done":true}.
bench() {
  label=$1; tmo=$2; shift 2
  batteryok || { echo "STOP battery $(date +%H:%M:%S)" >> $LOG; exit 3; }
  input keyevent KEYCODE_WAKEUP
  state "$label"
  rm -f $F
  echo "START $label $(date +%H:%M:%S) $*" >> $LOG
  am start -S -n $ACT --ez overLockScreen true --ez localApi true "$@" >/dev/null 2>&1
  i=0
  while [ $i -lt $tmo ]; do
    sleep 3; i=$((i+3))
    grep -q '"done":true' $F 2>/dev/null && break
  done
  sleep 1
  cp $F $OUT/$label.txt 2>/dev/null
  grep -q '"done":true' $F 2>/dev/null && echo "DONE $label $(date +%H:%M:%S) ${i}s" >> $LOG || echo "TIMEOUT $label $(date +%H:%M:%S)" >> $LOG
  am force-stop $PKG
  sleep 5
}

# cold <label> <n> <am extras...>: one unmeasured launch, then n cold launches (force-stop between),
# each waiting for the process's {"cold":...} line.
cold() {
  label=$1; n=$2; shift 2
  batteryok || { echo "STOP battery $(date +%H:%M:%S)" >> $LOG; exit 3; }
  input keyevent KEYCODE_WAKEUP
  state "$label"
  echo "START $label $(date +%H:%M:%S) $*" >> $LOG
  rm -f $F
  k=0
  while [ $k -le $n ]; do
    am force-stop $PKG
    sleep 4
    input keyevent KEYCODE_WAKEUP
    before=$(grep -c '"cold"' $F 2>/dev/null || echo 0)
    am start -n $ACT --ez overLockScreen true --ez localApi true "$@" >/dev/null 2>&1
    i=0
    while [ $i -lt 30 ]; do
      sleep 1; i=$((i+1))
      now=$(grep -c '"cold"' $F 2>/dev/null || echo 0)
      [ "$now" -gt "$before" ] && break
    done
    sleep 3
    k=$((k+1))
  done
  cp $F $OUT/$label.txt 2>/dev/null
  echo "DONE $label $(date +%H:%M:%S) $(grep -c '"cold"' $F)" >> $LOG
  am force-stop $PKG
  sleep 5
}

# taps <label> <n> <am extras...>: series I. One fresh process under a Perfetto trace; after it
# settles, n rounds of real touches through the input system: a quick tap on a list row (`input
# tap`; it opens the detail), the detail's header Back (a button), "Load 20" (a button; a new
# request), a held press on a row (`input swipe` in place, 300 ms; it opens the detail), Back again.
# 2.5 s apart so the app is idle (no frame) before each touch.
taps() {
  label=$1; n=$2; shift 2
  batteryok || { echo "STOP battery $(date +%H:%M:%S)" >> $LOG; exit 3; }
  input keyevent KEYCODE_WAKEUP
  state "$label"
  echo "START $label $(date +%H:%M:%S) $*" >> $LOG
  am force-stop $PKG
  sleep 2
  dur=$(( n * 12500 + 16000 ))
  { cat $D/trace.cfg; echo "duration_ms: $dur"; } | perfetto --txt -c - -o /data/misc/perfetto-traces/a12cp2-$label.pftrace >/dev/null 2>&1 &
  sleep 2
  am start -S -n $ACT --ez overLockScreen true --ez localApi true "$@" >/dev/null 2>&1
  sleep 10
  k=1
  while [ $k -le $n ]; do
    y=$((352 + 135 * ((k - 1) % 4)))
    echo "TAP row $k $(date +%H:%M:%S)" >> $LOG
    input tap 360 $y
    sleep 2.5
    echo "TAP back $k $(date +%H:%M:%S)" >> $LOG
    input tap 67 97
    sleep 2.5
    echo "TAP load $k $(date +%H:%M:%S)" >> $LOG
    input tap 180 195
    sleep 2.5
    echo "TAP held $k $(date +%H:%M:%S)" >> $LOG
    input swipe 360 $y 360 $y 300
    sleep 2.5
    echo "TAP back2 $k $(date +%H:%M:%S)" >> $LOG
    input tap 67 97
    sleep 2.5
    k=$((k+1))
  done
  wait
  echo "DONE $label $(date +%H:%M:%S) $(ls -la /data/misc/perfetto-traces/a12cp2-$label.pftrace | tr -s ' ' | cut -d' ' -f5)" >> $LOG
  am force-stop $PKG
  sleep 5
}
