# The 0.5.0 candidate's A12 reference (MS4 C11), 2026-11-26: checkpoint 2's series G and H harness
# (its master.sh), on NF5 R0's install (the sample's benchmark build on the fork, Long as BigInt),
# with a same-session native reference in both. No 1.28.0 anchor (no such build that night).
# Runs ON the phone:
#   adb shell '(nohup sh /data/local/tmp/ref1126/master.sh < /dev/null > /dev/null 2>&1 &)'
. /data/local/tmp/ref1126/lib.sh
N="--ei n 14 --ei warmup 2 --ei hold 100"
rm -f $D/master-done.txt
echo "MASTER $(date +%H:%M:%S)" >> $LOG
# --- G: cold start. Two rounds of native, the guest as shipped, the guest with prestart,prefetch;
# each: one unmeasured launch, then 4 cold launches (8 measured per arm).
for r in 1 2; do
  cold G-r$r-native 4 -e form native
  cold G-r$r-big-shipped 4 -e form guest
  cold G-r$r-big-pp 4 -e form guest -e x prestart,prefetch
done
echo "SERIES G END $(date +%H:%M:%S)" >> $LOG
# --- H: open (depth 1, prepared stack) and back, from the touch-down, hold 100; simple and heavy,
# press off (0.5.0's default) and on; native's nav as the same-session reference. Two rounds.
for r in 1 2; do
  bench H-off-r$r 400 -e form guest -e bench nav $N -e x prepared
  bench H-on-r$r 400 -e form guest -e bench nav $N -e x prepared,press
  bench H-heavy-off-r$r 400 -e form guest -e bench nav $N -e x prepared,heavy
  bench H-heavy-on-r$r 400 -e form guest -e bench nav $N -e x prepared,press,heavy
  bench H-native-r$r 400 -e form native -e bench nav $N
  bench H-native-heavy-r$r 400 -e form native -e bench nav $N -e x heavy
done
echo "SERIES H END $(date +%H:%M:%S)" >> $LOG
state END
echo "MASTER END $(date +%H:%M:%S)" >> $LOG
echo done > $D/master-done.txt
