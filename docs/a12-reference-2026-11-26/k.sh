# The 0.5.0 A12 reference (2026-11-26), series K's method (checkpoint 2's px.sh + px-all.sh): real
# touches (`input tap`) under a Perfetto trace AND a raw-frame screen recording (screenrecord
# --output-format=frames, 180x400 RGB888, one record per composited frame); per arm one fresh
# process, then 9 rounds of: a quick tap on a list row (opens the detail), the detail's header Back,
# Load 20 (native Compose's own button in both forms), 2.5 s apart. Then the frame diffs and the
# queries, on the phone. Guest = NF5 R0's install (the fork, Long as BigInt). arm() stops below 30%
# battery off the charger, as checkpoint 2's px.sh did (restored after the run; the run was on the
# charger, where it passes). Runs ON the phone:
#   adb shell '(nohup sh /data/local/tmp/ref1126/k.sh < /dev/null > /dev/null 2>&1 &)'
. /data/local/tmp/ref1126/lib.sh
rm -f $D/k-done.txt
arm() {
  label=$1; n=$2; shift 2
  batteryok || { echo "STOP battery $(date +%H:%M:%S)" >> $LOG; exit 3; }
  input keyevent KEYCODE_WAKEUP
  state "$label"
  echo "START $label $(date +%H:%M:%S) $*" >> $LOG
  am force-stop $PKG
  sleep 2
  dur=$(( n * 7500 + 22000 ))
  (cat $D/trace.cfg; echo "duration_ms: $dur") | perfetto --txt -c - -o /data/misc/perfetto-traces/ref1126-$label.pftrace >/dev/null 2>&1 &
  sleep 2
  am start -S -n $ACT --ez overLockScreen true --ez localApi true "$@" >/dev/null 2>&1
  sleep 10
  rm -f $OUT/$label.raw
  screenrecord --output-format=frames --size 180x400 $OUT/$label.raw >/dev/null 2>&1 &
  SR=$!
  sleep 3
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
    k=$((k+1))
  done
  sleep 1
  kill -INT $SR
  wait
  echo "DONE $label $(date +%H:%M:%S) raw=$(ls -l $OUT/$label.raw | tr -s ' ' | cut -d' ' -f5) trace=$(ls -l /data/misc/perfetto-traces/ref1126-$label.pftrace | tr -s ' ' | cut -d' ' -f5)" >> $LOG
  am force-stop $PKG
  sleep 3
}
echo "K $(date +%H:%M:%S)" >> $LOG
arm K-big 9 -e form guest
arm K-native 9 -e form native
state K-END
echo "K RECORDED $(date +%H:%M:%S)" >> $LOG
cd $OUT
for a in big native; do
  sh $D/px-diff.sh K-$a.raw K-$a-px.sql 2>diff-K-$a.log
  for q in main seq count; do
    cat K-$a-px.sql $D/q-px.sql $D/q-px-$q.sql > K-$a-$q.sql
    $D/trace_processor_shell -q K-$a-$q.sql /data/misc/perfetto-traces/ref1126-K-$a.pftrace 2>K-$a-$q.err | grep -v '^$' > K-$a-$q.csv
  done
done
echo "K END $(date +%H:%M:%S)" >> $LOG
echo done > $D/k-done.txt
