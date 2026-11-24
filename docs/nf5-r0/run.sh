#!/system/bin/sh
# NF5 R0 (2026-11-25): series H's arms, and the idle arm, under Perfetto, one fresh process each.
# Runs ON THE PHONE:
#   adb shell '(nohup sh /data/local/tmp/nf5floor/run.sh < /dev/null > /data/local/tmp/nf5floor/log.txt 2>&1 &)'
# then poll for /data/local/tmp/nf5floor/done.txt (about 5 minutes). `run.sh <arm>` runs one arm.
D=/data/local/tmp/nf5floor
P=dev.keliver.sample
A=$P/dev.keliver.sample.host.threeway.ThreewayActivity
R=/sdcard/Android/data/$P/files/threeway-results.txt
arm() { # $1 label, $2 experiments (x=), $3 trace length in seconds, $4 the tap's hold in ms
  am force-stop $P
  sleep 5
  input keyevent KEYCODE_WAKEUP
  N=$(cat $R 2>/dev/null | wc -l)
  { date; uptime; getprop ro.build.version.release; getprop ro.build.version.sdk
    dumpsys battery | grep -E 'level|temperature|AC powered|USB powered'
    dumpsys thermalservice | grep -m1 'Thermal Status'; top -b -n 1 | head -5; } > $D/$1.state.txt
  sed "s/DURATION/${3}000/" $D/cfg.txt > $D/cfg-$1.txt
  # perfetto in the foreground with its config on stdin (a file argument fails under SELinux; -b with
  # stdin, or & under nohup, writes nothing); the app starts 2 s later from a subshell.
  ( sleep 2; am start -S -n $A --ez overLockScreen true --ez localApi true -e form guest \
      -e bench nav --ei n 14 --ei warmup 2 --ei hold $4 -e x $2 ) &
  cat $D/cfg-$1.txt | perfetto --txt -c - -o /data/misc/perfetto-traces/nf5-$1.pftrace
  wait
  tail -n +$((N + 1)) $R > $D/$1.results.txt
  am force-stop $P
}
run() {
  case "$1" in
    # The rule's arm (R0-1): the press prepares the detail on the DOWN, and the finger lifts 400 ms
    # later, after the preparation (about 200 ms on the A12) has finished: the click reaches an idle
    # guest, as (B)'s will. The row has no long-press handler (`clickable` only), so a 400 ms hold
    # still clicks on the UP.
    simple-on-idle) arm simple-on-idle prepared,press,trace 75 400 ;;
    simple-on) arm simple-on prepared,press,trace 60 100 ;;
    simple-off) arm simple-off prepared,trace 60 100 ;;
    heavy-on) arm heavy-on heavy,prepared,press,trace 75 100 ;;
    *) echo "run.sh: unknown arm $1" >&2; return 2 ;;
  esac
}
rm -f $D/done.txt
if [ -n "$1" ]; then run "$1"; else
  for a in simple-on-idle simple-on simple-off heavy-on; do run $a; done
fi
echo done > $D/done.txt
