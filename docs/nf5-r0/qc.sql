-- QC (after prelude.sql, measure.sql and <arm>.bench.sql, which analyse.sh writes from the results
-- file): the identity check. Per open, the guest's own click -> composed (`composed - click` in the
-- results file, whole ms on the guest's clock) must lie inside [check_lo - 1, check_hi + 1]; at
-- least 10 of opens 3-14 must, or the opens were misidentified. `pass` is on every row.
select q.i, b.bench_ms, round(q.check_lo, 1) as check_lo, round(q.check_hi, 1) as check_hi,
  b.bench_ms between q.check_lo - 1 and q.check_hi + 1 as ok,
  (select count(*) from q q2 join bench b2 using (i) where q2.i > 2
     and b2.bench_ms between q2.check_lo - 1 and q2.check_hi + 1) as ok_of_3_to_14,
  (select count(*) from q q2 join bench b2 using (i) where q2.i > 2
     and b2.bench_ms between q2.check_lo - 1 and q2.check_hi + 1) >= 10 as pass
from q join bench b using (i) order by q.i;
