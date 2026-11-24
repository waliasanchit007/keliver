-- NF5 R0 measure (after prelude.sql; UPNAME replaced by the UP message's name from q0b).

-- U: the driver's UP message, the last of its name before the click event E. E0: the first guest
-- event after U. With the press on, the UP sends two events back to back (the press's release and
-- the click); a runs to the first, b covers both (n_ev counts them).
create perfetto table up0 as
select s.i, (select m.ts from main_sl m where m.depth = 0 and m.name = 'UPNAME' and m.ts < s.e_ts
  order by m.ts desc limit 1) as u_ts
from step s;

create perfetto table up as
select u.i, u.u_ts,
  (select min(z.ts) from zt z where z.name glob 'zl:sendEvent*' and z.ts >= u.u_ts and z.ts <= s.e_ts) as e0_ts,
  (select count(*) from zt z where z.name glob 'zl:sendEvent*' and z.ts >= u.u_ts and z.ts <= s.e_ts) as n_ev
from up0 u join step s using (i);

-- The fastest cluster's CPUs (on the A12, the 2.3 GHz cluster). Empty when the trace has no CPU
-- frequency track (an emulator): d_running_fast is then 0.
create perfetto table cpu_fast as
select t.cpu from cpu_counter_track t join counter c on c.track_id = t.id
where t.name = 'cpufreq' group by t.cpu
having max(c.value) >= (select max(c2.value) from counter c2
  join cpu_counter_track t2 on c2.track_id = t2.id where t2.name = 'cpufreq');

-- The Zipline thread's states from F's start to C (the guest's recomposition up to its report).
-- Sleeping (S) and uninterruptible (D) are split out of `other`; q3 names what they blocked on
-- (sched_blocked_reason).
create perfetto table zst as
select s.i,
  sum(case when t.state = 'Running' then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as run_ns,
  sum(case when t.state = 'Running' and t.cpu in (select cpu from cpu_fast)
      then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as run_fast_ns,
  sum(case when t.state in ('R', 'R+') then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as runnable_ns,
  sum(case when t.state not in ('Running', 'R', 'R+')
      then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as other_ns,
  sum(case when t.state = 'S' then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as sleep_ns,
  sum(case when t.state in ('D', 'DK') then min(t.ts + t.dur, s.c_ts) - max(t.ts, s.f_ts) else 0 end) as dstate_ns
from step s join thread_state t on t.utid = (select utid from zl_th)
  and t.ts < s.c_ts and t.ts + t.dur > s.f_ts
group by s.i;

-- The Zipline thread running between E's end and F's start (other work on it while the guest waits
-- for its frame tick): not a wait, so W leaves it out (R0-3).
create perfetto table zst_c as
select s.i,
  coalesce(sum(case when t.state = 'Running'
    then min(t.ts + t.dur, s.f_ts) - max(t.ts, s.e_ts + s.e_dur) else 0 end), 0) as run_ns
from step s left join thread_state t on t.utid = (select utid from zl_th)
  and t.ts < s.f_ts and t.ts + t.dur > s.e_ts + s.e_dur
group by s.i;

-- c split (R0-2): the guest's frame tick is a Choreographer frame callback on the main thread
-- (AndroidChoreographerFrameClock), which launches sendFrame on the Zipline thread. K = the last
-- main-thread doFrame that starts before F; its `animation` section (the callbacks) if present.
-- c1: E's end -> K (the wait for the vsync and the main thread); c2: K -> F (the hop back to the
-- Zipline thread). c1 is negative when K began before E ended.
create perfetto table cbk as
select s.i, d.ts as k_df_ts,
  coalesce((select min(a.ts) from main_sl a where a.name = 'animation'
    and a.ts >= d.ts and a.ts < d.ts + d.dur), d.ts) as k_ts
from step s join main_sl d on d.id = (select d2.id from main_sl d2
  where d2.name glob 'Choreographer#doFrame*' and d2.ts < s.f_ts order by d2.ts desc limit 1);

-- The batch on the main thread: the first non-frame message after the batch was sent (expected:
-- kotlinx.coroutines' dispatch of the apply; q1 prints its name), then the next doFrame, its layout,
-- and its present (the app's actual frame timeline row for the frame's vsync id; Android 12+).
create perfetto table host_a as
select s.i, m.ts as a_ts, m.dur as a_dur, m.name as a_name from step s
join main_sl m on m.id = (select m2.id from main_sl m2 where m2.depth = 0 and m2.ts >= s.sc_end
  and m2.name glob 'msg *' and m2.name not glob '*Choreographer*' order by m2.ts limit 1);

create perfetto table host as
select a.*, d.ts as df_ts, d.dur as df_dur,
  (select coalesce(max(x.dur), 0) from main_sl x where x.name glob '*measureAndLayout*'
     and x.ts >= d.ts and x.ts < d.ts + d.dur) as ml_ns,
  (select max(f.ts + f.dur) from actual_frame_timeline_slice f
     where f.upid in (select upid from app_upid)
       and f.name = cast(case when d.name glob '*resynced to *'
         then cast(substr(d.name, instr(d.name, 'resynced to ') + 12) as int)
         else cast(substr(d.name, 23) as int) end as text)) as present
from host_a a join main_sl d on d.id = (select d2.id from main_sl d2
  where d2.name glob 'Choreographer#doFrame*' and d2.ts >= a.a_ts + a.a_dur order by d2.ts limit 1);

-- One row per open, in ms. a: UP → click event starts (the hop to the Zipline thread). b: the
-- click event (the guest's onClick and the navigation). c: event end → the frame that composes the
-- detail starts (the wait for the guest's next frame tick), split into c1 and c2 (above);
-- c_zl_running: the Zipline thread busy with something else meanwhile. d: frame start → the
-- guest's `composed` report, split by the Zipline thread's state (running; of it on the fast
-- cluster; runnable, i.e. preempted or waiting for a CPU; anything else: sleeping or blocked, and of
-- it S and D). e: composed → the batch sent. f: batch sent → the main thread's apply message; g:
-- the apply. h: apply end → the next doFrame; i: that frame, and the layout in it. j: frame end →
-- presented. W (R0-3): the waits inside click → composed, (c - c_zl_running) + d_runnable +
-- d_other. check_lo/hi: the guest's own click → composed (results file: composed - click) must lie
-- between them, +-1 ms, or the open was misidentified. check_hi runs from the FIRST event after the
-- UP: with the press on, the click is the first of the two (the dry run: from E's start, 3 of 12
-- opens missed by 1.1-2 ms, the length of the event before E).
create perfetto table q as
select s.i, h.a_name, s.sc_name, u.n_ev,
  (u.e0_ts - u.u_ts) / 1e6 as a_up_to_event,
  (s.e_ts + s.e_dur - u.e0_ts) / 1e6 as b_event,
  (s.f_ts - s.e_ts - s.e_dur) / 1e6 as c_event_to_frame,
  (k.k_ts - s.e_ts - s.e_dur) / 1e6 as c1_event_to_callback,
  (s.f_ts - k.k_ts) / 1e6 as c2_callback_to_frame,
  zc.run_ns / 1e6 as c_zl_running,
  (s.c_ts - s.f_ts) / 1e6 as d_frame_to_composed,
  z.run_ns / 1e6 as d_running, z.run_fast_ns / 1e6 as d_running_fast,
  z.runnable_ns / 1e6 as d_runnable, z.other_ns / 1e6 as d_other,
  z.sleep_ns / 1e6 as d_other_sleep, z.dstate_ns / 1e6 as d_other_d,
  (s.f_ts - s.e_ts - s.e_dur - zc.run_ns + z.runnable_ns + z.other_ns) / 1e6 as w_waits,
  (s.sc_end - s.c_ts) / 1e6 as e_composed_to_sent,
  (h.a_ts - s.sc_end) / 1e6 as f_sent_to_apply, h.a_dur / 1e6 as g_apply,
  (h.df_ts - h.a_ts - h.a_dur) / 1e6 as h_apply_to_frame,
  h.df_dur / 1e6 as i_frame, h.ml_ns / 1e6 as i_layout,
  (h.present - h.df_ts - h.df_dur) / 1e6 as j_frame_to_present,
  (h.present - u.u_ts) / 1e6 as total_up_to_present,
  (s.c_ts - s.e_ts - s.e_dur) / 1e6 as check_lo, (s.c_ts - u.e0_ts) / 1e6 as check_hi
from step s join up u using (i) join zst z using (i) join zst_c zc using (i) join cbk k using (i)
  join host h using (i);
