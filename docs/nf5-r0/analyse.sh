#!/system/bin/sh
# NF5 R0: the queries for one arm, ON THE PHONE, with the trace_processor_shell next to this file.
#   sh analyse.sh <arm> groups   q0a (the tap groups) and q0b (the main thread's messages before E)
#   sh analyse.sh <arm> measure  q1 (per open), q2 (medians, W, the projection), q3 (what d_other
#                                 blocked on); needs <arm>.upname.txt, the UP message's exact name
#                                 as q0b prints it (with the leading "msg ")
# Optional <arm>.opengroups.txt: the open groups' numbers from q0a, comma-separated (for example
# 3,5,7,...), when the default (the last 28 groups, an open first) is wrong for the run (R0-6).
# Only the last statement of a file prints, so each query runs as the prelude plus that query, in
# its own trace_processor_shell (about a minute each on the A12).
cd /data/local/tmp/nf5floor || exit 1
L="${1:?arm}"; what="${2:?groups|measure}"
T=/data/misc/perfetto-traces/nf5-$L.pftrace
[ -f "$T" ] || { echo "analyse.sh: no trace $T" >&2; exit 1; }
sed "s|OPENGROUPS|$(cat $L.opengroups.txt 2>/dev/null)|" prelude.sql > $L.p.sql
q() { # $1 query name; the files before it come from $2...
  n=$1; shift
  cat "$@" $n.sql > $L.r.sql
  ./trace_processor_shell -q $L.r.sql $T > $L.$n.txt 2> $L.$n.err || { echo "analyse.sh: $L $n failed:" >&2; tail -5 $L.$n.err >&2; return 1; }
  echo "== $L $n"; cat $L.$n.txt
}
case "$what" in
  groups) q q0a $L.p.sql && q q0b $L.p.sql ;;
  measure)
    [ -s $L.upname.txt ] || { echo "analyse.sh: put the UP message's name from $L.q0b.txt into $L.upname.txt" >&2; exit 1; }
    sed "s|UPNAME|$(cat $L.upname.txt)|" measure.sql > $L.m.sql
    q q1 $L.p.sql $L.m.sql && q q2 $L.p.sql $L.m.sql && q q3 $L.p.sql $L.m.sql || exit 1
    # The bench's own click -> composed per open (ms, from the guest's clock), for q1's
    # check_lo..check_hi (+-1 ms): at least 10 of opens 3-14 must lie inside, or the open was
    # misidentified.
    echo "== $L results: i composed-click"
    grep '"bench":"open"' $L.results.txt | while read -r line; do
      i=$(echo "$line" | sed 's/.*"i":\([0-9]*\).*/\1/'); c=$(echo "$line" | sed 's/.*"click":\([0-9]*\).*/\1/')
      k=$(echo "$line" | sed 's/.*"composed":\([0-9]*\).*/\1/'); echo "$i $((k - c))"
    done | tee $L.check.txt ;;
  *) echo "analyse.sh: groups or measure" >&2; exit 2 ;;
esac
