#!/system/bin/sh
# NF5 R1: the analysis, ON THE PHONE, in /data/local/tmp/nf5r1 with trace_processor_shell beside it
# (docs/nf5-r1/README.md, step 6).
#   sh analyse.sh <arm>   the bench's own marks per open and their medians (from <arm>.results.txt),
#                         then the trace: q1 (per open), the identity check, q2 (medians), q3
#                         (frames around opens 3-5)
#   sh analyse.sh bar     the pre-registered bar, from the arms' medians (run after every arm)
#   sh analyse.sh all     every arm, then bar, into analyse-all.txt, then analyse-done.txt; detached:
#     adb shell '(nohup sh /data/local/tmp/nf5r1/analyse.sh all < /dev/null > /dev/null 2>&1 &)'
# Only the last statement of a file prints, so each query runs as the prelude, measure.sql and that
# query, in its own trace_processor_shell (about a minute each on the A12, as R0's).
cd /data/local/tmp/nf5r1 || exit 1
ARMS="native-simple simple-reveal-idle simple-reveal simple-off native-heavy heavy-reveal-idle heavy-reveal heavy-off"

# The bench's marks per open (ms from the UP; `drawn` is the first draw of the bench's frame), as
# <arm>.bench.txt, and their medians over opens 3-14 as <arm>.bench-med.txt ("col median n").
# revealed / revealDrawn / revealRecorded / revealLive: the host reveal's marks (R1Reveal);
# composed_click = composed - click, the guest's click -> composed on its own clock.
bench() {
  L=$1
  # A mark the line does not have prints as "-", so the columns stay aligned.
  grep '"bench":"open"' $L.results.txt | awk '
    function get(k,   m) {
      if (match($0, "\"" k "\":-?[0-9]+")) { m = substr($0, RSTART, RLENGTH); sub(/.*:/, "", m); return m }
      return "-"
    }
    BEGIN { print "i revealed revealDrawn revealRecorded revealLive click composed composed_click drawn nextHit" }
    {
      c = get("click"); k = get("composed"); ck = (c != "-" && k != "-") ? k - c : "-"
      print get("i"), get("revealed"), get("revealDrawn"), get("revealRecorded"), get("revealLive"), c, k, ck, get("drawn"), get("n_nextHit")
    }' > $L.bench.txt
  awk '
    NR == 1 { for (j = 1; j <= NF; j++) name[j] = $j; cols = NF; next }
    $1 > 2 { for (j = 2; j <= cols; j++) if ($j != "-") { n[j]++; v[j, n[j]] = $j + 0 } }
    END {
      for (j = 2; j <= cols; j++) {
        m = n[j] + 0
        for (a = 2; a <= m; a++) { x = v[j, a]; b = a - 1; while (b >= 1 && v[j, b] > x) { v[j, b + 1] = v[j, b]; b-- } v[j, b + 1] = x }
        if (m == 0) med = ""; else if (m % 2) med = v[j, (m + 1) / 2]; else med = (v[j, m / 2] + v[j, m / 2 + 1]) / 2
        print name[j], med, m
      }
    }' $L.bench.txt > $L.bench-med.txt
  echo "== $L bench, per open (ms from the UP)"; cat $L.bench.txt
  echo "== $L bench, medians over opens 3-14 (col median n)"; cat $L.bench-med.txt
}

# The identity check (after bench and q1): per open, the trace's click -> composed (q1's `ec`, the
# `drawn` rows) against the bench's (`composed_click`, the guest's own clock in whole ms). It passes
# when at least 10 of opens 3-14 agree within 2 ms; otherwise the trace's click or composed is not the
# open's, and the arm's guest columns are not read. Native arms have no guest step ("of 0").
check() {
  L=$1
  grep '"drawn"' $L.q1.txt | tr -d '"' | tr ',' ' ' > $L.ec.tmp
  echo "== $L check (i trace_ec bench_composed_click diff ok)"
  awk 'FNR == NR { if (FNR > 1) cc[$1] = $8; next }
    {
      i = $1; ec = $16; b = cc[i]
      if (ec == "[NULL]" || ec == "" || b == "" || b == "-") { print i, ec, b, "-", "-"; next }
      d = ec - b; ok = (d >= -2 && d <= 2) ? 1 : 0
      if (i > 2) { n++; k += ok }
      print i, ec, b, d, ok
    }
    END {
      if (n == 0) print "pass n/a (no guest step: a native arm)"
      else print "pass", (k >= 10) ? 1 : 0, "(" k + 0, "of", n + 0, "opens 3-14 within 2 ms)"
    }' $L.bench.txt $L.ec.tmp
}

q() { # $1 arm, $2 query name
  cat prelude.sql measure.sql $2.sql > $1.r.sql
  ./trace_processor_shell -q $1.r.sql /data/misc/perfetto-traces/nf5r1-$1.pftrace > $1.$2.txt 2> $1.$2.err \
    || { echo "analyse.sh: $1 $2 failed:" >&2; tail -5 $1.$2.err >&2; return 1; }
  echo "== $1 $2"; grep -v '^$' $1.$2.txt
}

# A median from <arm>.bench-med.txt ($2: the column) or <arm>.q2.txt ($2: "reveal.rec_ms", ...).
bmed() { awk -v c="$2" '$1 == c { print $2 }' $1.bench-med.txt 2>/dev/null; }
bn() { awk -v c="$2" '$1 == c { print $3 }' $1.bench-med.txt 2>/dev/null; }
tmed() { grep "\"$2\"" $1.q2.txt 2>/dev/null | head -1 | awk -F, '{ gsub(/"/, "", $2); print $2 }'; }

bar() {
  echo "== bar (pre-registered, docs/nf5-r1/README.md): medians over opens 3-14, ms from the UP"
  for s in simple heavy; do
    nd=$(bmed native-$s drawn)
    echo "-- $s: native's first draw (native-$s drawn) = $nd (n=$(bn native-$s drawn)); bar 1 = $nd + 16.7"
    for a in $s-reveal-idle $s-reveal; do
      rd=$(bmed $a revealDrawn); rec=$(tmed $a reveal.rec_ms)
      v1=$(awk -v r="$rd" -v n="$nd" 'BEGIN { if (r == "" || n == "") print "no data"; else if (r + 0 <= n + 16.7) print "MET"; else print "MISSED" }')
      v2=$(awk -v r="$rec" 'BEGIN { if (r == "") print "no data"; else if (r + 0 < 10) print "MET"; else print "MISSED" }')
      echo "   $a: reveal's first draw (revealDrawn) = $rd (n=$(bn $a revealDrawn)): bar 1 $v1;" \
        "its Record View#draw() (trace reveal.rec_ms) = $rec: bar 2 (< 10) $v2"
      echo "   $a: reveal's frame: main-thread end $(tmed $a reveal.f_end), buffer queued $(tmed $a reveal.q_drew)" \
        "(R0's rule $(tmed $a reveal.q_r0)), row end $(tmed $a reveal.row_end), display frame end $(tmed $a reveal.sf_end);" \
        "trace opens with a reveal $(tmed $a count.reveal) of 12"
    done
    echo "   native-$s frame: main-thread end $(tmed native-$s drawn.f_end), buffer queued $(tmed native-$s drawn.q_drew)" \
      "(R0's rule $(tmed native-$s drawn.q_r0)), row end $(tmed native-$s drawn.row_end), display frame end" \
      "$(tmed native-$s drawn.sf_end), Record View#draw() $(tmed native-$s drawn.rec_ms)"
    echo "   click -> composed (bench composed_click; trace guest.ec), reveal off / on / on with the idle measure:" \
      "$(bmed $s-off composed_click) / $(bmed $s-reveal composed_click) / $(bmed $s-reveal-idle composed_click);" \
      "trace $(tmed $s-off guest.ec) / $(tmed $s-reveal guest.ec) / $(tmed $s-reveal-idle guest.ec)"
    echo "   the guest's detail drawn (bench drawn), off / on / idle: $(bmed $s-off drawn) / $(bmed $s-reveal drawn) / $(bmed $s-reveal-idle drawn)"
  done
  echo "-- R1 passes only if simple-reveal-idle and heavy-reveal-idle each meet bars 1 and 2 (README);" \
    "the arms without the idle measure say whether that measure is needed."
}

case "$1" in
  all)
    rm -f analyse-done.txt
    for a in $ARMS; do
      bench $a; q $a q1 && check $a; q $a q2; q $a q3
    done > analyse-all.txt 2>&1
    bar >> analyse-all.txt 2>&1
    echo done > analyse-done.txt ;;
  bar) bar ;;
  "") echo "analyse.sh: <arm>|bar|all" >&2; exit 2 ;;
  *)
    [ -f $1.results.txt ] || { echo "analyse.sh: no $1.results.txt" >&2; exit 1; }
    [ -f /data/misc/perfetto-traces/nf5r1-$1.pftrace ] || { echo "analyse.sh: no trace for $1" >&2; exit 1; }
    bench $1; q $1 q1 && check $1; q $1 q2; q $1 q3 ;;
esac
