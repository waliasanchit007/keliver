-- Q4 (after prelude.sql and measure.sql): q3 counted, one row per open (EVERY open), ms from the UP.
-- It says, open by open, whether a RenderThread-only buffer came before the first frame after the
-- UP, and whether that frame's own draw queued anything (the emulator dry run: the reveal's own
-- frame queued nothing in 47 of 48 opens).
--   hold      the DOWN -> the UP (the driver's sleep: no main-thread frame runs in it)
--   rt_pre    RenderThread-only buffers in the 200 ms before the DOWN (an animation already running)
--   rt_hold   RenderThread-only buffers in the hold, DOWN -> UP; rt_first, the first one's end
--   f1_start  the first `Choreographer#doFrame <id>` that starts at or after the UP: its start
--   f1_ds     its own `DrawFrames <id>`'s length (empty: it had none)
--   f1_q      1 when that DrawFrames queued a buffer, 0 when it queued none, empty when there was none
--   f1_row    its actual frame-timeline row's present type
--   q_first   the first buffer queued by a DrawFrames that starts at or after the UP (its end)
--   rt_post   RenderThread-only buffers in the 50 ms after the UP
-- A RenderThread-only buffer (measure.sql's rto) is an outermost queueBuffer with no DrawFrames
-- around it: HWUI's own frame for its animations (`dispatchFrameCallbacks`), which no main-thread
-- frame synced.

create perfetto table o4 as
select o.i, o.u_ts,
  (select max(m.ts) from main_sl m where m.name glob 'bench:down open *' and m.ts < o.u_ts
     and m.ts > o.u_ts - 1000000000) as d_ts,
  (select f.id from main_sl f where f.name glob 'Choreographer#doFrame [0-9]*' and f.ts >= o.u_ts
     order by f.ts limit 1) as f1_id
from opens o;

select o.i,
  round((o.u_ts - o.d_ts) / 1e6, 1) as hold,
  (select count(*) from rto r where r.e >= o.d_ts - 200000000 and r.e < o.d_ts) as rt_pre,
  (select count(*) from rto r where r.e >= o.d_ts and r.e < o.u_ts) as rt_hold,
  (select round((min(r.e) - o.u_ts) / 1e6, 1) from rto r where r.e >= o.d_ts and r.e < o.u_ts) as rt_first,
  round((f.ts - o.u_ts) / 1e6, 1) as f1_start,
  round(d.dur / 1e6, 1) as f1_ds,
  case when d.id is null then null when d.q_end is null then 0 else 1 end as f1_q,
  (select a.present_type from actual_frame_timeline_slice a where a.upid in (select upid from app_upid)
     and a.name = substr(f.name, 23) order by a.ts limit 1) as f1_row,
  (select round((min(x.q_end) - o.u_ts) / 1e6, 1) from rtd x where x.ts >= o.u_ts and x.q_end is not null) as q_first,
  (select count(*) from rto r where r.e >= o.u_ts and r.e < o.u_ts + 50000000) as rt_post
from o4 o
left join main_sl f on f.id = o.f1_id
left join rtd d on d.id = (select x.id from rtd x where x.vsync = cast(substr(f.name, 23) as int) order by x.ts limit 1)
order by o.i;
