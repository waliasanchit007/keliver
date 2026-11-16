#!/system/bin/sh
# Runs ON the device, started by run-device.sh (start) under setsid nohup; POSIX sh (mksh/toybox).
# Args: <rounds> <warmup> <iterations> <cooldown seconds> <stay awake 0|1> <mode>...
# Writes results/combine.jsonl (one run.js ENGINE_RUN object per run, or an "error" object),
# results/state.jsonl (battery, thermal status, wakefulness before and after each round, with the
# raw dumpsys output in results/state-r<round>-<when>.txt), results/progress.log and results/DONE.
D=/data/local/tmp/keliver-engine
cd "$D" || exit 1
rounds=$1 warmup=$2 iterations=$3 cooldown=$4 stay=$5
shift 5
modes="$*"
echo $$ > driver.pid
R=results
mkdir -p $R
: > $R/combine.jsonl
: > $R/state.jsonl
# The Mac's order (driver/rerun-all.sh's cfwd/crev); round 2 runs it backwards.
fwd="q21-Os:as-shipped q26fix-Os:as-shipped q26fix-O2:as-shipped q26fix-Os:bigint q26fix-O2:bigint"
rev="q26fix-O2:bigint q26fix-Os:bigint q26fix-O2:as-shipped q26fix-Os:as-shipped q21-Os:as-shipped"
log() { echo "$(date +%H:%M:%S) $*" >> $R/progress.log; }

restore() {
  if [ -f $R/stay_on.old ]; then
    old=$(cat $R/stay_on.old)
    if [ "$old" = null ]; then settings delete global stay_on_while_plugged_in && rm -f $R/stay_on.old
    else settings put global stay_on_while_plugged_in "$old" && rm -f $R/stay_on.old; fi
    log "restored stay_on_while_plugged_in=$old (now $(settings get global stay_on_while_plugged_in))"
  fi
}
trap 'restore; log "driver killed"; exit 1' TERM INT HUP
if [ "$stay" = 1 ]; then
  [ -f $R/stay_on.old ] || settings get global stay_on_while_plugged_in > $R/stay_on.old
  settings put global stay_on_while_plugged_in 7   # AC, USB and wireless
fi

state() { # <round> <before|after>
  f=$R/state-r$1-$2.txt
  { echo "== date"; date; echo "== battery"; dumpsys battery; echo "== thermalservice"; dumpsys thermalservice
    echo "== thermal zones"; for z in /sys/class/thermal/thermal_zone*; do
      echo "$(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null)"; done
    echo "== power"; dumpsys power | grep -E 'mWakefulness=|mStayOn|mIsPowered|mPlugType' ; } > $f 2>&1
  level=$(sed -n 's/^ *level: //p' $f | head -1)
  temp=$(sed -n 's/^ *temperature: //p' $f | head -1)
  bstatus=$(sed -n 's/^ *status: //p' $f | head -1)
  ac=$(sed -n 's/^ *AC powered: //p' $f | head -1)
  usb=$(sed -n 's/^ *USB powered: //p' $f | head -1)
  thermal=$(sed -n 's/^Thermal Status: //p' $f | head -1)
  wake=$(sed -n 's/^ *mWakefulness=//p' $f | head -1)
  echo "{\"round\":$1,\"when\":\"$2\",\"time\":\"$(date +%Y-%m-%dT%H:%M:%S)\",\"batteryLevel\":\"$level\",\"batteryTempDeciC\":\"$temp\",\"batteryStatus\":\"$bstatus\",\"acPowered\":\"$ac\",\"usbPowered\":\"$usb\",\"thermalStatus\":\"$thermal\",\"wakefulness\":\"$wake\"}" >> $R/state.jsonl
}

to=""
command -v timeout > /dev/null 2>&1 && to="timeout 900"
log "start: $rounds rounds, warmup $warmup, iterations $iterations, modes $modes, model $(getprop ro.product.model), Android $(getprop ro.build.version.release)"
r=1
while [ $r -le "$rounds" ]; do
  if [ $r = 2 ]; then order=$rev; else order=$fwd; fi
  state $r before
  for spec in $order; do
    b=${spec%%:*} w=${spec#*:}
    for m in $modes; do
      label="$b/$w/r$r"
      $to ./bin/$b/qjs --std run.js workload/$w $m "$warmup" "$iterations" "$label" > $R/last.out 2>&1
      rc=$?
      line=$(grep '^ENGINE_RUN ' $R/last.out | sed 's/^ENGINE_RUN //')
      if [ -n "$line" ]; then
        echo "$line" >> $R/combine.jsonl
      else
        echo "{\"label\":\"$label\",\"mode\":\"$m\",\"error\":\"exit $rc\"}" >> $R/combine.jsonl
        cp $R/last.out "$R/fail-$b-$w-r$r-$m.txt"
      fi
      log "$label $m rc=$rc"
    done
  done
  state $r after
  log "round $r done"
  if [ $r -lt "$rounds" ] && [ "$cooldown" -gt 0 ]; then sleep "$cooldown"; fi
  r=$((r + 1))
done
restore
rm -f $R/last.out
log "all rounds done"
echo done > $R/DONE
