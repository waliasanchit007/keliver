#!/system/bin/sh
# NF5 R1: the reveal-only arm and native's arm of the same detail, simple and heavy, each in a fresh
# process under its own Perfetto trace (docs/nf5-r1/README.md). Runs ON THE PHONE:
#   adb shell '(nohup sh /data/local/tmp/nf5r1/run.sh < /dev/null > /data/local/tmp/nf5r1/log.txt 2>&1 &)'
# then poll for /data/local/tmp/nf5r1/done.txt (about 10 minutes): `done`, or `stopped: battery ...`
# when the battery stop ended the run before an arm (exit 3). `run.sh <arm>` runs one arm.
# Every am/pm call names dev.keliver.sample; nothing changes a setting.
D=/data/local/tmp/nf5r1
P=dev.keliver.sample
A=$P/dev.keliver.sample.host.threeway.ThreewayActivity
R=/sdcard/Android/data/$P/files/threeway-results.txt
ARMS="native-simple simple-reveal-idle simple-reveal simple-off native-heavy heavy-reveal-idle heavy-reveal heavy-off"
# The C11 reference's battery stop (docs/a12-reference-2026-11-26/lib.sh): true when the battery is
# at 30% or more, or a charger (AC or USB) powers the phone.
batteryok() {
  dumpsys battery | awk '/level:/{l=$2}/AC powered/{a=$3}/USB powered/{u=$3}END{exit !(l>=30 || a=="true" || u=="true")}'
}
arm() { # $1 label, $2 form, $3 experiments (x=), $4 trace length in seconds
  # Before each arm: below 30% and not charging, stop with the reason in done.txt (README, step 0).
  batteryok || { echo "stopped: battery below 30% and not charging, before $1 ($(date))" | tee $D/done.txt; exit 3; }
  am force-stop $P
  sleep 5
  input keyevent KEYCODE_WAKEUP
  N=$(cat $R 2>/dev/null | wc -l)
  { date; uptime; getprop ro.build.version.release; getprop ro.build.version.sdk
    dumpsys battery | grep -E 'level|temperature|AC powered|USB powered'
    dumpsys thermalservice | grep -m1 'Thermal Status'; top -b -n 1 | head -5
    # The installed APK, to tie this arm to the build record (README, step 1).
    for f in $(pm path $P | sed 's/^package://'); do sha256sum "$f"; done; } > $D/$1.state.txt 2>&1
  sed "s/DURATION/${4}000/" $D/cfg.txt > $D/cfg-$1.txt
  # perfetto in the foreground with its config on stdin (R0: a file argument fails under SELinux;
  # -b with stdin, or & under nohup, writes nothing); the app starts 2 s later from a subshell.
  # Times are from the UP (no --ei hold: the driver's tap holds 40 ms and lifts in one message).
  ( sleep 2; am start -S -n $A --ez overLockScreen true --ez localApi true -e form $2 \
      -e bench nav --ei n 14 --ei warmup 2 -e x $3 ) &
  cat $D/cfg-$1.txt | perfetto --txt -c - -o /data/misc/perfetto-traces/nf5r1-$1.pftrace
  wait
  tail -n +$((N + 1)) $R > $D/$1.results.txt
  am force-stop $P
}
run() {
  case "$1" in
    # Native's arm of the same detail (the bar's reference: its first draw is the detail with its data).
    native-simple) arm native-simple native trace 50 ;;
    native-heavy) arm native-heavy native heavy,trace 50 ;;
    # The reveal-only arm: the next open's detail composed and hidden while idle; the host shows it on
    # the click, before the guest runs (reveal), with and without measuring it while idle (idlemeasure).
    simple-reveal-idle) arm simple-reveal-idle guest r1prep,reveal,idlemeasure,trace 60 ;;
    simple-reveal) arm simple-reveal guest r1prep,reveal,trace 60 ;;
    # The same detail without the reveal: the guest's click shows it (click -> composed, reveal off).
    simple-off) arm simple-off guest r1prep,trace 60 ;;
    heavy-reveal-idle) arm heavy-reveal-idle guest heavy,r1prep,reveal,idlemeasure,trace 75 ;;
    heavy-reveal) arm heavy-reveal guest heavy,r1prep,reveal,trace 75 ;;
    heavy-off) arm heavy-off guest heavy,r1prep,trace 75 ;;
    *) echo "run.sh: unknown arm $1" >&2; return 2 ;;
  esac
}
rm -f $D/done.txt
if [ -n "$1" ]; then run "$1"; else
  for a in $ARMS; do run $a; done
fi
echo done > $D/done.txt
