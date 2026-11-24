-- Q3 (after prelude.sql and measure.sql): what the Zipline thread was doing when it was neither
-- running nor runnable inside d (F's start -> C), over opens 3 to 14: total ms and opens by state
-- and the kernel function it blocked in (sched_blocked_reason; null without that event, or for a
-- plain sleep). d_other's mechanism is named from this (R0-2).
select t.state, t.blocked_function, t.io_wait,
  round(sum(min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts)) / 1e6, 1) as total_ms,
  count(distinct s.i) as opens, count(*) as intervals
from step s join thread_state t on t.utid = (select utid from zl_th)
  and t.ts < s.c_ts and t.ts + t.dur > s.f_ts
where s.i > 2 and t.state not in ('Running', 'R', 'R+')
group by t.state, t.blocked_function, t.io_wait order by total_ms desc limit 20;
