-- QC (after prelude.sql, measure.sql and <arm>.bench.sql, which analyse.sh writes from the results
-- file): the identity check. Per open, the guest's own click -> composed (`composed - click` in the
-- results file, whole ms on the guest's clock) must lie inside [check_lo - 1, check_hi + 1]; at
-- least 10 of opens 3-14 must, or the opens were misidentified. `pass` is on every row. Also per
-- open, `idle` = the UP - the end of the last zl:* slice that began before it (ms), and how many of
-- opens 3-14 had it > 0 (R-L1): on simple-on-idle only those count in the rule, which needs 10 of
-- 12; on the press-on arms a negative `idle` is expected and only reported.
select q.i, b.bench_ms, round(q.check_lo, 1) as check_lo, round(q.check_hi, 1) as check_hi,
  b.bench_ms between q.check_lo - 1 and q.check_hi + 1 as ok,
  (select count(*) from q q2 join bench b2 using (i) where q2.i > 2
     and b2.bench_ms between q2.check_lo - 1 and q2.check_hi + 1) as ok_of_3_to_14,
  (select count(*) from q q2 join bench b2 using (i) where q2.i > 2
     and b2.bench_ms between q2.check_lo - 1 and q2.check_hi + 1) >= 10 as pass,
  round(q.idle_at_up, 1) as idle,
  (select count(*) from q q2 where q2.i > 2 and q2.idle_at_up > 0) as idle_of_3_to_14
from q join bench b using (i) order by q.i;
