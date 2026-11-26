# The 0.5.0 A12 reference: medians from the results files, ON the phone (toybox sort/awk/sed).
#   sh an.sh nav <file>...    open/back, i > 2 (the bench's warm-up of 2 dropped): drawn, click,
#                             composed - click, drawn - click per iteration; back's firstDraw when present
#   sh an.sh cold <file>...   each file's first "cold" line dropped (unmeasured launch); the marks
# Prints: median / p90 / min-max, then every value (per-iteration data). an.txt is the 2026-11-26
# run's output, from before dataFrameEnd was added here (its values are in THREE_WAY_BENCHMARK.md).
st() { sort -n | awk '{a[NR]=$1} END { if (NR == 0) { print "-"; exit }
  m = (NR % 2) ? a[(NR + 1) / 2] : (a[NR / 2] + a[NR / 2 + 1]) / 2
  p = int(NR * 0.9 + 0.999999); if (p < 1) p = 1
  s = ""; for (i = 1; i <= NR; i++) s = s " " a[i]
  printf "%s / %s / %s-%s (n=%d) [%s ]\n", m, a[p], a[1], a[NR], NR, s }'; }
f() { sed -n "s/.*\"$1\":\([-0-9.]*\).*/\1/p"; }
d() { awk -v a="$1" -v b="$2" '{ x = ""; y = ""; n = split($0, kv, ",")
  for (i = 1; i <= n; i++) { if (kv[i] ~ "\"" a "\":") { split(kv[i], p, ":"); x = p[2] }
    if (kv[i] ~ "\"" b "\":") { split(kv[i], q, ":"); y = q[2] } }
  gsub(/[^-0-9.]/, "", x); gsub(/[^-0-9.]/, "", y); if (x != "" && y != "") print y - x }'; }
mode=$1; shift
case $mode in
  nav)
    tmp=/data/local/tmp/ref1126/an.tmp
    cat "$@" | grep -E '"bench":"(open|back)"' | sed 's/^[0-9]* //' \
      | awk '{ if (match($0, /"i":[0-9]+/)) { i = substr($0, RSTART + 4, RLENGTH - 4) + 0; if (i > 2) print } }' > $tmp
    for b in open back; do
      echo "  $b drawn:          $(grep "\"bench\":\"$b\"" $tmp | f drawn | st)"
      echo "  $b click:          $(grep "\"bench\":\"$b\"" $tmp | f click | st)"
      echo "  $b click->composed: $(grep "\"bench\":\"$b\"" $tmp | d click composed | st)"
      echo "  $b click->drawn:   $(grep "\"bench\":\"$b\"" $tmp | d click drawn | st)"
      grep -q "\"bench\":\"$b\".*firstDraw" $tmp && echo "  $b firstDraw:      $(grep "\"bench\":\"$b\"" $tmp | f firstDraw | st)"
    done
    echo "  i=1 open: $(cat "$@" | grep '"bench":"open"' | grep '"i":1,' | sed 's/^[0-9]* //' | head -1)" ;;
  cold)
    tmp=/data/local/tmp/ref1126/an.tmp
    for x in "$@"; do grep '"cold"' $x | sed 's/^[0-9]* //' | tail -n +2; done > $tmp
    for k in dataDrawn dataFrameEnd firstDraw codeLoadStart codeLoadSuccess composed; do
      echo "  $k: $(f $k < $tmp | st)"
    done
    echo "  request->response: $(d request response < $tmp | st)"
    echo "  parsed->composed:  $(d parsed composed < $tmp | st)"
    echo "  composed->dataDrawn: $(d composed dataDrawn < $tmp | st)" ;;
esac
