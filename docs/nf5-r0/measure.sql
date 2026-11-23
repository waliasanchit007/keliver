-- NF5 R0 measure (after prelude.sql; UPNAME replaced by the UP message's name from q0b).

-- U: the driver's UP message, the last of its name before the click event E.
create perfetto table up as
select s.i, (select m.ts from main_sl m where m.depth = 0 and m.name = 'UPNAME' and m.ts < s.e_ts
  order by m.ts desc limit 1) as u_ts
from step s;

-- The fastest cluster's CPUs (on the A12, the 2.3 GHz cluster).
create perfetto table cpu_fast as
select t.cpu from cpu_counter_track t join counter c on c.track_id = t.id
where t.name = 'cpufreq' group by t.cpu
having max(c.value) >= (select max(c2.value) from counter c2
  join cpu_counter_track t2 on c2.track_id = t2.id where t2.name = 'cpufreq');

-- The Zipline thread's states from F's start to C (the guest's recomposition up to its report).
create perfetto table zst as
select s.i,
  sum(case when t.state = 'Running' then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as run_ns,
  sum(case when t.state = 'Running' and t.cpu in (select cpu from cpu_fast)
      then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as run_fast_ns,
  sum(case when t.state in ('R', 'R+') then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as runnable_ns,
  sum(case when t.state not in ('Running', 'R', 'R+')
      then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as other_ns
from step s join thread_state t on t.utid = (select utid from zl_th)
  and t.ts < s.c_ts and t.ts + t.dur > s.f_ts
group by s.i;

-- The Zipline thread running between E's end and F's start (other work on it while the guest waits
-- for its frame tick).
create perfetto table zst_c as
select s.i,
  coalesce(sum(case when t.state = 'Running'
    then min(t.ts + t.dur, s.f_ts) - max(t.ts, s.e_ts + s.e_dur) else 0 end), 0) as run_ns
from step s left join thread_state t on t.utid = (select utid from zl_th)
  and t.ts < s.f_ts and t.ts + t.dur > s.e_ts + s.e_dur
group by s.i;

-- The batch on the main thread: the first non-frame message after the batch was sent (expected:
-- kotlinx.coroutines' dispatch of the apply; q1 prints its name), then the next doFrame, its layout,
-- and its present (the app's actual frame timeline row for the frame's vsync id).
create perfetto table host_a as
select s.i, m.ts as a_ts, m.dur as a_dur, m.name as a_name from step s
join main_sl m on m.id = (select m2.id from main_sl m2 where m2.depth = 0 and m2.ts >= s.sc_end
  and m2.name like 'msg %' and m2.name not like '%Choreographer%' order by m2.ts limit 1);

create perfetto table host as
select a.*, d.ts as df_ts, d.dur as df_dur,
  (select coalesce(max(x.dur), 0) from main_sl x where x.name like '%measureAndLayout%'
     and x.ts >= d.ts and x.ts < d.ts + d.dur) as ml_ns,
  (select max(f.ts + f.dur) from actual_frame_timeline_slice f
     where f.upid in (select upid from app_upid)
       and f.name = cast(case when d.name like '%resynced to %'
         then cast(substr(d.name, instr(d.name, 'resynced to ') + 12) as int)
         else cast(substr(d.name, 23) as int) end as text)) as present
from host_a a join main_sl d on d.id = (select d2.id from main_sl d2
  where d2.name like 'Choreographer#doFrame%' and d2.ts >= a.a_ts + a.a_dur order by d2.ts limit 1);

-- One row per open, in ms. a: UP → click event starts (the hop to the Zipline thread). b: the
-- click event (the guest's onClick and the navigation). c: event end → the frame that composes the
-- detail starts (the wait for the guest's next frame tick); c_zl_running: the Zipline thread busy
-- with something else meanwhile. d: frame start → the guest's `composed` report, split by the
-- Zipline thread's state (running; of it on the fast cluster; runnable, i.e. preempted or waiting
-- for a CPU; anything else: sleeping or blocked). e: composed → the batch sent. f: batch sent →
-- the main thread's apply message; g: the apply. h: apply end → the next doFrame; i: that frame,
-- and the layout in it. j: frame end → presented. check_lo/hi: the guest's own click → composed
-- (results file: composed - click) must lie between them, +-1 ms, or the open was misidentified.
create perfetto table q as
select s.i, h.a_name, s.sc_name,
  (s.e_ts - u.u_ts) / 1e6 as a_up_to_event,
  s.e_dur / 1e6 as b_event,
  (s.f_ts - s.e_ts - s.e_dur) / 1e6 as c_event_to_frame,
  zc.run_ns / 1e6 as c_zl_running,
  (s.c_ts - s.f_ts) / 1e6 as d_frame_to_composed,
  z.run_ns / 1e6 as d_running, z.run_fast_ns / 1e6 as d_running_fast,
  z.runnable_ns / 1e6 as d_runnable, z.other_ns / 1e6 as d_other,
  (s.sc_end - s.c_ts) / 1e6 as e_composed_to_sent,
  (h.a_ts - s.sc_end) / 1e6 as f_sent_to_apply, h.a_dur / 1e6 as g_apply,
  (h.df_ts - h.a_ts - h.a_dur) / 1e6 as h_apply_to_frame,
  h.df_dur / 1e6 as i_frame, h.ml_ns / 1e6 as i_layout,
  (h.present - h.df_ts - h.df_dur) / 1e6 as j_frame_to_present,
  (h.present - u.u_ts) / 1e6 as total_up_to_present,
  (s.c_ts - s.e_ts - s.e_dur) / 1e6 as check_lo, (s.c_ts - s.e_ts) / 1e6 as check_hi
from step s join up u using (i) join zst z using (i) join zst_c zc using (i) join host h using (i);
