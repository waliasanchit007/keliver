# The 0.5.0 A12 reference (2026-11-26), after master.sh: back, retained (series A's method: system
# back with the host's retained back, bench=sysback, x=retainhost; firstDraw = the host showing the
# retained list, drawn = the guest's confirming frame), simple and heavy, two rounds; then series K
# (k.sh). Runs ON the phone:
#   adb shell '(nohup sh /data/local/tmp/ref1126/h2.sh < /dev/null > /dev/null 2>&1 &)'
. /data/local/tmp/ref1126/lib.sh
rm -f $D/h2-done.txt
echo "H2 $(date +%H:%M:%S)" >> $LOG
for r in 1 2; do
  bench H-sysback-r$r 400 -e form guest -e bench sysback --ei n 14 --ei warmup 2 -e x retainhost
  bench H-sysback-heavy-r$r 400 -e form guest -e bench sysback --ei n 14 --ei warmup 2 -e x retainhost,heavy
done
echo "H2 END $(date +%H:%M:%S)" >> $LOG
echo done > $D/h2-done.txt
sh $D/k.sh
