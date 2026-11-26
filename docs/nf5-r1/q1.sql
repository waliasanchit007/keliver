-- Q1 (after prelude.sql and measure.sql): one row per open and frame kind, ms from the open's UP,
-- so every median of q2 can be derived again by hand. Columns: measure.sql's header. analyse.sh's
-- identity check reads `ec`, the 16th column; its bar reads `q_vs`, the 20th.
select i, kind, round(marker, 1) as marker, round(f_start, 1) as f_start, round(f_end, 1) as f_end,
  round(rec_ms, 1) as rec_ms, round(ml_ms, 1) as ml_ms, round(q_same, 1) as q_same, round(ds_ms, 1) as ds_ms,
  round(q_drew, 1) as q_drew, round(dw_ms, 1) as dw_ms, drew_own, round(q_r0, 1) as q_r0,
  round(row_end, 1) as row_end, round(sf_end, 1) as sf_end, round(ec, 1) as ec, round(e_wait, 1) as e_wait,
  k_reveal, round(k_ms, 1) as k_ms, q_vs
from q order by kind desc, i;
