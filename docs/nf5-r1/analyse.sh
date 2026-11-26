#!/system/bin/sh
# NF5 R1: the analysis, ON THE PHONE, in /data/local/tmp/nf5r1 with trace_processor_shell beside it
# (docs/nf5-r1/README.md, step 6).
#   sh analyse.sh <arm>   the bench's own marks per open and their medians (from <arm>.results.txt),
#                         then the trace: q1 (per open), the identity check, q2 (medians), q3 (every
#                         open's frames, from its DOWN) and q4 (q3 counted, per open)
#   sh analyse.sh bar     the pre-registered bar, from the arms' files (run after every arm): each
#                         arm's validity, then the verdicts
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

# The identity check (after bench and q1), into <arm>.check.txt: per open, the trace's click ->
# composed (q1's `ec`, the `drawn` rows) against the bench's (`composed_click`, the guest's own clock
# in whole ms). It passes when at least 10 of opens 3-14 agree within 2 ms; otherwise the trace's
# click or composed is not the open's, and the arm does not count. Native arms have no guest step.
check() {
  L=$1
  grep '"drawn"' $L.q1.txt | tr -d '"' | tr ',' ' ' > $L.ec.tmp
  { echo "== $L check (i trace_ec bench_composed_click diff ok)"
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
    }' $L.bench.txt $L.ec.tmp; } > $L.check.txt
  cat $L.check.txt
}

q() { # $1 arm, $2 query name
  cat prelude.sql measure.sql $2.sql > $1.r.sql
  ./trace_processor_shell -q $1.r.sql /data/misc/perfetto-traces/nf5r1-$1.pftrace > $1.$2.txt 2> $1.$2.err \
    || { echo "analyse.sh: $1 $2 failed:" >&2; tail -5 $1.$2.err >&2; return 1; }
  echo "== $1 $2"; grep -v '^$' $1.$2.txt
}

# A median from <arm>.bench-med.txt ($2: the column, bmed; its n, bn) or <arm>.q2.txt ($2:
# "reveal.rec_ms", ...; tmed). A value the file does not have prints as nothing.
bmed() { awk -v c="$2" '$1 == c { print $2 }' $1.bench-med.txt 2>/dev/null; }
bn() { awk -v c="$2" '$1 == c { print $3 }' $1.bench-med.txt 2>/dev/null; }
tmed() {
  grep "^\"$2\"," $1.q2.txt 2>/dev/null | head -1 \
    | awk -F, '{ gsub(/"/, "", $2); if ($2 != "[NULL]" && $2 != "") printf "%g\n", $2 }'
}
# x <= y + 16.7: "yes", "no", or "no data".
within() { awk -v r="$1" -v n="$2" 'BEGIN { if (r == "" || n == "") print "no data"; else if (r + 0 <= n + 16.7) print "yes"; else print "no" }'; }

# An arm's validity (README, "When an arm counts"). Sets OK (1: the arm counts) and VAL (the readings
# it rests on, or "INVALID (<what failed>; ...)").
validity() { # $1 arm, $2 native|reveal|off
  a=$1; why=""
  ro=$(grep -c '"bench":"open"' $a.results.txt 2>/dev/null); ro=${ro:-0}
  rd=$(grep -c '"done":true' $a.results.txt 2>/dev/null); rd=${rd:-0}
  rt=$(grep -c '"timeout":true' $a.results.txt 2>/dev/null); rt=${rt:-0}
  to=$(tmed $a count.opens)
  [ "$ro" = 14 ] || why="$why results opens $ro;"
  [ "$rd" -ge 1 ] || why="$why no done line;"
  [ "$rt" = 0 ] || why="$why $rt timeouts;"
  [ "$to" = 14 ] || why="$why trace opens ${to:-none};"
  info="results opens $ro, done $rd, timeouts $rt; trace opens (count.opens) ${to:--}"
  if [ "$2" = reveal ]; then
    cr=$(tmed $a count.reveal); br=$(bn $a revealed)
    [ "${cr:-0}" -ge 10 ] || why="$why trace reveals ${cr:-none} of 12;"
    [ "${br:-0}" -ge 10 ] || why="$why bench reveals ${br:-none} of 12;"
    info="$info; reveals of opens 3-14: trace (count.reveal) ${cr:--}, bench (revealed) ${br:--}"
  fi
  if [ "$2" != native ]; then
    # The lowest nextHit over all 14 opens ("-": an open without it).
    nh=$(awk 'NR > 1 { if ($10 == "-") miss = 1; else if (m == "" || $10 + 0 < m + 0) m = $10 }
      END { print (miss || m == "") ? "-" : m }' $a.bench.txt 2>/dev/null)
    ck=$(grep '^pass' $a.check.txt 2>/dev/null | head -1)
    [ "$nh" = 1 ] || why="$why lowest nextHit ${nh:-none};"
    [ "$(echo "$ck" | awk '{ print $2 }')" = 1 ] || why="$why identity check ${ck:-not run};"
    info="$info; lowest nextHit ${nh:--}; identity check: ${ck:-not run}"
  fi
  if [ -z "$why" ]; then OK=1; VAL="valid ($info)"; else OK=0; VAL="INVALID ($why ) ($info)"; fi
}

# q4's per-open counts over ALL 14 opens (q4: rt_pre $3, rt_hold $4, f1_q $8).
q4count() {
  tr -d '"' < $1.q4.txt 2>/dev/null | tr ',' ' ' | awk '
    $1 ~ /^[0-9]+$/ { n++; if ($3 + 0 > 0) p++; if ($4 + 0 > 0) h++; if ($8 == "0") z++ }
    END { printf "q4, all %d opens: a RenderThread-only buffer in the 200 ms before the DOWN in %d, in the tap'"'"'s hold in %d; the first frame after the UP queued no buffer in %d\n", n, p, h, z }'
}

# bar [noripple]: the bar from the arms' files. With an argument X, from the arms named <arm>-X (the
# ripple controls, step 4b): a diagnostic beside the pre-registered verdict, never a verdict itself.
# Arms without a results file (the reveal arm without the idle measure, the off arm) print "not run".
bar() {
  X=${1:+-$1}
  if [ -z "$X" ]; then
    echo "== bar (pre-registered, docs/nf5-r1/README.md; bar 1 read on both readings, the maintainer's ruling of"
    echo "   2026-11-26, which the owner may still change before the run): medians over opens 3-14, ms from the UP"
  else
    echo "== bar on the arms named *$X (README, step 4b): a DIAGNOSTIC beside the pre-registered verdict above,"
    echo "   not a verdict: medians over opens 3-14, ms from the UP"
  fi
  R1OK=1; R1VALID=1
  for s in simple heavy; do
    NA=native-$s$X
    validity $NA native; nok=$OK
    nd=$(bmed $NA drawn); nq=$(tmed $NA drawn.q_drew); nv=$(tmed $NA drawn.q_vs)
    echo "-- $s: $NA: $VAL"
    echo "   $NA: first draw (drawn) $nd (n=$(bn $NA drawn)); buffer queued (drawn.q_drew) $nq;" \
      "q_vs (vsyncs, UP -> its buffer) median $nv; $(q4count $NA)"
    echo "   $NA frame: main-thread end $(tmed $NA drawn.f_end), R0's rule $(tmed $NA drawn.q_r0)," \
      "row end $(tmed $NA drawn.row_end), display frame end $(tmed $NA drawn.sf_end)," \
      "Record View#draw() $(tmed $NA drawn.rec_ms); queued by its own vsync $(tmed $NA count.drawn.drew_own) of 12"
    for a in $s-reveal-idle$X $s-reveal$X; do
      if [ "$a" = $s-reveal$X ] && [ ! -f $a.results.txt ]; then echo "   $a: not run"; continue; fi
      validity $a reveal; aok=$OK
      rd=$(bmed $a revealDrawn); rq=$(tmed $a reveal.q_drew); rec=$(tmed $a reveal.rec_ms)
      ia=$(within "$rd" "$nd"); ib=$(within "$rq" "$nq")
      if [ "$aok" != 1 ] || [ "$nok" != 1 ]; then
        v1="INVALID (no verdict: $( [ "$aok" != 1 ] && echo "$a" ) $( [ "$nok" != 1 ] && echo "$NA" ) does not count)"
        v2=INVALID
      else
        if [ "$ia" = "no data" ] || [ "$ib" = "no data" ]; then v1="no data"
        elif [ "$ia" = yes ] && [ "$ib" = yes ]; then v1=MET
        elif [ "$ia" = yes ]; then v1="NOT MET (no buffer in the reveal's frame)"
        elif [ "$ib" = yes ]; then v1="NOT MET (first draw)"
        else v1="NOT MET (first draw and buffer)"; fi
        v2=$(awk -v r="$rec" 'BEGIN { if (r == "") print "no data"; else if (r + 0 < 10) print "MET"; else print "NOT MET" }')
      fi
      echo "   $a: $VAL"
      echo "   $a: bar 1 $v1: (a) first draw (revealDrawn) $rd <= native's $nd + 16.7: $ia;" \
        "(b) buffer queued (reveal.q_drew) $rq <= native's $nq + 16.7: $ib"
      if [ "$a" = $s-reveal-idle$X ]; then
        echo "   $a: bar 2 $v2: the reveal's Record View#draw() (reveal.rec_ms) $rec < 10"
        { [ "$v1" = MET ] && [ "$v2" = MET ]; } || R1OK=0
        [ "$aok" = 1 ] && [ "$nok" = 1 ] || R1VALID=0
      else
        echo "   $a: Record View#draw() (reveal.rec_ms) $rec (bar 2 judges the idle arm; reported)"
      fi
      # The per-open reading in vsyncs (reported, not a condition): opens 3-14 whose reveal buffer was
      # queued no later than the vsync after native's own (q_vs <= native's median q_vs + 1).
      tr -d '"' < $a.q1.txt 2>/dev/null | tr ',' ' ' | awk -v a="$a" -v nv="$nv" '
        $2 == "reveal" && $1 > 2 { all = all " " ($20 == "[NULL]" ? "-" : $20); if ($20 != "[NULL]" && $20 != "") { n++; if (nv != "" && $20 + 0 <= nv + 1) k++ } }
        END { if (nv == "") print "   " a ": per open, in vsyncs: no native q_vs";
          else printf "   %s: per open, in vsyncs: the reveal'"'"'s buffer queued no later than the vsync after native'"'"'s own (q_vs <= %g + 1) in %d of %d opens; q_vs, opens 3-14:%s\n", a, nv, k, n, all }'
      echo "   $a: reveal's frame: main-thread end $(tmed $a reveal.f_end), R0's rule $(tmed $a reveal.q_r0)," \
        "row end $(tmed $a reveal.row_end), display frame end $(tmed $a reveal.sf_end);" \
        "queued by its own vsync $(tmed $a count.reveal.drew_own) of 12, by a RenderThread-only frame" \
        "$(tmed $a count.reveal.q_rt) (q_src); $(q4count $a)"
    done
    if [ -f $s-off$X.results.txt ]; then
      validity $s-off$X off
      echo "   $s-off$X: $VAL; $(q4count $s-off$X)"
    else echo "   $s-off$X: not run"; fi
    echo "   click -> composed (bench composed_click; trace guest.ec), reveal off / on / on with the idle measure:" \
      "$(bmed $s-off$X composed_click) / $(bmed $s-reveal$X composed_click) / $(bmed $s-reveal-idle$X composed_click);" \
      "trace $(tmed $s-off$X guest.ec) / $(tmed $s-reveal$X guest.ec) / $(tmed $s-reveal-idle$X guest.ec)"
    echo "   the guest's detail drawn (bench drawn), off / on / idle:" \
      "$(bmed $s-off$X drawn) / $(bmed $s-reveal$X drawn) / $(bmed $s-reveal-idle$X drawn)"
  done
  if [ -n "$X" ]; then L="-- with *$X (diagnostic, not R1's verdict):"; else L="-- R1:"; fi
  if [ "$R1VALID" != 1 ]; then echo "$L NO VERDICT (an arm the verdict rests on does not count; README: rerun it once)"
  elif [ "$R1OK" = 1 ]; then echo "$L PASSES (simple-reveal-idle$X and heavy-reveal-idle$X each meet bars 1 and 2)"
  else echo "$L DOES NOT PASS (simple-reveal-idle$X or heavy-reveal-idle$X misses bar 1 or bar 2)"; fi
  [ -z "$X" ] && echo "   The arms without the idle measure say whether that measure is needed (README)."
}

arm() { bench $1; q $1 q1 && check $1; q $1 q2; q $1 q3; q $1 q4; }

case "$1" in
  all)
    # ARMS, then any other arm that ran (the ripple controls, step 4b; the A/B's controls), then the
    # bar, and the noripple diagnostic when its native arm ran.
    rm -f analyse-done.txt
    { for a in $ARMS; do arm $a; done
      for f in *.results.txt; do
        a=${f%.results.txt}
        case " $ARMS " in *" $a "*) ;; *) [ -f /data/misc/perfetto-traces/nf5r1-$a.pftrace ] && arm $a ;; esac
      done; } > analyse-all.txt 2>&1
    bar >> analyse-all.txt 2>&1
    [ -f native-simple-noripple.results.txt ] && bar noripple >> analyse-all.txt 2>&1
    echo done > analyse-done.txt ;;
  bar) bar "$2" ;;
  "") echo "analyse.sh: <arm>|bar [noripple]|all" >&2; exit 2 ;;
  *)
    [ -f $1.results.txt ] || { echo "analyse.sh: no $1.results.txt" >&2; exit 1; }
    [ -f /data/misc/perfetto-traces/nf5r1-$1.pftrace ] || { echo "analyse.sh: no trace for $1" >&2; exit 1; }
    arm $1 ;;
esac
