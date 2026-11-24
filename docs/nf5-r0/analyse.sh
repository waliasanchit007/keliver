#!/system/bin/sh
# NF5 R0: the queries for one arm, ON THE PHONE, with the trace_processor_shell next to this file.
#   sh analyse.sh <arm> groups   q0a (the tap groups) and q0b (the main thread's messages before E)
#   sh analyse.sh <arm> measure  q1 (per open), q2 (medians, W, the projection), q3 (what d_other
#                                 blocked on), qc (the identity check against the results file)
#   sh analyse.sh all            groups and measure for the four arms, into analyse-all.txt, then
#                                 analyse-done.txt (about 20 minutes on the A12); run it detached:
#     adb shell '(nohup sh /data/local/tmp/nf5floor/analyse.sh all < /dev/null > /dev/null 2>&1 &)'
# The UP message's exact name (with the leading "msg "): <arm>.upname.txt if present, else
# upname.txt (the dry run's build; R8 numbers the lambda, so check it against q0b).
# Optional <arm>.opengroups.txt: the open groups' numbers from q0a, comma-separated (for example
# 3,5,7,...), when the default (the last 28 groups, an open first) is wrong for the run (R0-6).
# Only the last statement of a file prints, so each query runs as the prelude plus that query, in
# its own trace_processor_shell (about a minute each on the A12).
cd /data/local/tmp/nf5floor || exit 1
if [ "$1" = all ]; then
  rm -f analyse-done.txt
  for a in simple-on-idle simple-on simple-off heavy-on; do
    sh analyse.sh $a groups; sh analyse.sh $a measure
  done > analyse-all.txt 2>&1
  echo done > analyse-done.txt
  exit 0
fi
L="${1:?arm|all}"; what="${2:?groups|measure}"
T=/data/misc/perfetto-traces/nf5-$L.pftrace
[ -f "$T" ] || { echo "analyse.sh: no trace $T" >&2; exit 1; }
sed "s|OPENGROUPS|$(cat $L.opengroups.txt 2>/dev/null)|" prelude.sql > $L.p.sql
q() { # $1 query name; the files before it come from $2...
  n=$1; shift
  cat "$@" $n.sql > $L.r.sql
  ./trace_processor_shell -q $L.r.sql $T > $L.$n.txt 2> $L.$n.err || { echo "analyse.sh: $L $n failed:" >&2; tail -5 $L.$n.err >&2; return 1; }
  echo "== $L $n"; grep -v '^$' $L.$n.txt
}
case "$what" in
  groups) q q0a $L.p.sql && q q0b $L.p.sql ;;
  measure)
    U=$L.upname.txt; [ -s $U ] || U=upname.txt
    [ -s $U ] || { echo "analyse.sh: put the UP message's name from $L.q0b.txt into $L.upname.txt" >&2; exit 1; }
    echo "== $L UP message: $(cat $U) (from $U)"
    sed "s|UPNAME|$(cat $U)|" measure.sql > $L.m.sql
    # The bench's own click -> composed per open (whole ms, the guest's clock), as a table for qc.
    grep '"bench":"open"' $L.results.txt | while read -r line; do
      i=$(echo "$line" | sed 's/.*"i":\([0-9]*\).*/\1/'); c=$(echo "$line" | sed 's/.*"click":\([0-9]*\).*/\1/')
      k=$(echo "$line" | sed 's/.*"composed":\([0-9]*\).*/\1/'); echo "select $i as i, $((k - c)) as bench_ms union all"
    done > $L.bench.tmp
    { echo "create perfetto table bench as"; cat $L.bench.tmp; echo "select null, null where 0;"; } > $L.bench.sql
    q q1 $L.p.sql $L.m.sql && q q2 $L.p.sql $L.m.sql && q q3 $L.p.sql $L.m.sql \
      && q qc $L.p.sql $L.m.sql $L.bench.sql || exit 1 ;;
  *) echo "analyse.sh: groups or measure" >&2; exit 2 ;;
esac
