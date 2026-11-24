-- Q3 (after prelude.sql and measure.sql): what the Zipline thread was doing when it was neither
-- running nor runnable inside d (F's start -> C), over opens 3 to 14: total ms and opens by state,
-- the kernel function a D (uninterruptible) state blocked in (sched_blocked_reason, named only with
-- cfg.txt's symbolize_ksyms; null without kernel symbols, and for an S state) and whether it was
-- I/O, and the thread that woke it (sched_waking; for an S state the mechanism is its waker: a
-- lock or a message from that thread). trace_processor v45 keeps the waker on the runnable state
-- that FOLLOWS the sleep, not on the sleep (emulator dry run, 2026-11-24). d_other's mechanism is
-- named from this (R0-2).
select t.state, t.blocked_function, t.io_wait,
  (select w.name from thread_state n join thread w on w.utid = n.waker_utid
     where n.utid = t.utid and n.ts = t.ts + t.dur) as waker,
  round(sum(min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts)) / 1e6, 1) as total_ms,
  count(distinct s.i) as opens, count(*) as intervals
from step s join thread_state t on t.utid = (select utid from zl_th)
  and t.ts < s.c_ts and t.ts + t.dur > s.f_ts
where s.i > 2 and t.state not in ('Running', 'R', 'R+')
group by t.state, t.blocked_function, t.io_wait, waker order by total_ms desc limit 20;
