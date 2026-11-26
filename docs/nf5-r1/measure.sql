-- NF5 R1 measure (after prelude.sql): per open, the readings of each frame R1 reads (`reveal` and
-- `drawn`, prelude's fr), and the guest's step after the click. Times in ms from the open's UP.
--
-- R0's lesson (THREE_WAY_BENCHMARK.md, "NF5 R0"): measure to the frame that DREW the content, and
-- name the frame-end reading. On the A12 the press's ripple ran on the RenderThread after the UP
-- and queued its own buffer, so the main thread's frame could be drawn by the RenderThread a vsync
-- later, and that vsync's app frame-timeline row could be zero-length. So each frame has these
-- readings, all printed:
--   f_end   the main thread's frame end (the doFrame's end): the targets' "frame end", like native's
--           28 ms (SHOW_BEFORE_COMPUTE section 1);
--   q_drew  the buffer queued by the RenderThread draw that drew it: the first `DrawFrames` from
--           the doFrame on that queues a buffer, the end of its first `queueBuffer`; drew_own, 1 when
--           that DrawFrames is the doFrame's own vsync's, 0 when a later frame's (vsync ids are
--           tokens, not counts: they step by more than 1 per vsync). A DrawFrames that queues
--           nothing (R0: under 1 ms, the ripple's vsync; the dry run: a late frame's) is passed over;
--   q_same  the same for the doFrame's own `DrawFrames <vsync>` (empty when it queued nothing);
--   q_r0    R0's rule (frame-end.sql, the reading R0 recorded): the first `DrawFrames` of at least
--           2 ms from the doFrame on. It differs from q_drew when the draw that queued the buffer
--           took under 2 ms (the emulator dry run: the reveal's own frame, 1.7 ms, passed over for
--           the next one, 34 ms later);
--   row_end the app's actual frame-timeline row for the doFrame's vsync, when it is not zero-length
--           (about the buffer queued, R0);
--   sf_end  SurfaceFlinger's display frame that row points to (the display's "presented"; R0
--           checked it on one open only: about 45 ms after the row);
--   q_vs    q_drew in vsyncs: how many of the app's vsyncs fell between the UP and that buffer's
--           queueing (0: queued before the first vsync after the UP). The app's vsync grid is its
--           expected frame-timeline starts (one per app frame, all on the grid), with the period
--           from their steps (vsync.period_ms in q2). The bar's per-open reading compares it with
--           native's (README, "The bar").
-- Also: rec_ms, the `Record View#draw()` that recorded the frame (for `reveal`: the one holding the
-- r1:revealDrawn marker; for `drawn`: the first in that doFrame ending after the marker), and
-- ml_ms, Compose's measure and layout inside it (`*measureAndLayout*`).

create perfetto table sfu as select upid from process where name glob '*surfaceflinger*';

-- The app's vsync grid: its expected frame-timeline starts. The period is the mean of the steps
-- between consecutive starts that are at least the shortest step over 5 ms and under 1.5 times it
-- (consecutive vsyncs: the guest form draws one frame per vsync after each open).
create perfetto table ev as
select distinct e.ts from expected_frame_timeline_slice e where e.upid in (select upid from app_upid);

create perfetto table evd as
select ts - lag(ts) over (order by ts) as d from ev;

create perfetto table vper as
select avg(d) as p from evd
where d >= (select min(d) from evd where d > 5000000) and d < 1.5 * (select min(d) from evd where d > 5000000);

-- The RenderThread's draws, each with the end of its first queueBuffer (null: it queued none).
create perfetto table rtd as
select d.id, d.ts, d.dur, cast(substr(d.name, 12) as int) as vsync,
  (select b.ts + b.dur from rt_sl b where b.name = 'queueBuffer' and b.ts >= d.ts and b.ts < d.ts + d.dur
     order by b.ts limit 1) as q_end
from rt_sl d where d.name glob 'DrawFrames [0-9]*';

create perfetto table rd as
select r.i, r.kind, r.u_ts, r.m_ts, r.f_ts, r.f_dur, r.vsync,
  rec.ts as rec_ts, rec.dur as rec_dur,
  (select coalesce(max(x.dur), 0) from main_sl x where x.name glob '*measureAndLayout*'
     and x.ts >= rec.ts and x.ts < rec.ts + rec.dur) as ml_dur,
  ds.dur as ds_dur, ds.q_end as qs_end,
  dw.vsync as dw_vsync, dw.dur as dw_dur, dw.q_end as qw_end,
  d0.q_end as q0_end,
  (select max(v.ts) from ev v where v.ts <= r.u_ts) as g0,
  (select max(a.ts + a.dur) from actual_frame_timeline_slice a where a.upid in (select upid from app_upid)
     and a.name = cast(r.vsync as text) and a.dur > 0) as row_end,
  (select max(d.ts + d.dur) from actual_frame_timeline_slice d where d.upid in (select upid from sfu)
     and d.name = (select cast(a.display_frame_token as text) from actual_frame_timeline_slice a
       where a.upid in (select upid from app_upid) and a.name = cast(r.vsync as text) and a.dur > 0
       order by a.ts limit 1)) as sf_end
from fr r
left join main_sl rec on rec.id = (select x.id from main_sl x where x.name = 'Record View#draw()'
  and x.ts >= r.f_ts and x.ts < r.f_ts + r.f_dur and x.ts + x.dur >= r.m_ts order by x.ts limit 1)
left join rtd ds on ds.id = (select x.id from rtd x where x.vsync = r.vsync order by x.ts limit 1)
left join rtd dw on dw.id = (select x.id from rtd x where x.ts >= r.f_ts and x.q_end is not null
  order by x.ts limit 1)
left join rtd d0 on d0.id = (select x.id from rtd x where x.ts >= r.f_ts and x.dur >= 2000000
  order by x.ts limit 1);

-- The guest's step after the click, per open: E = the first zl:sendEvent in the open's window (the
-- click: no press is declared, and the DOWN sends nothing); C = the first zl:composed after E (the
-- detail's report); G = the zl:sendFrame that holds C; K = the last main-thread doFrame that starts
-- before G (its callbacks tick the guest's frame clock, AndroidChoreographerFrameClock).
-- ec: E's start -> C, the trace's click -> composed (the bench's `composed - click` within about
-- 1 ms); e_wait: E's end -> G (the wait for the guest's frame tick); k_reveal: 1 when K is the
-- reveal's frame (the reveal's frame ticked the guest), k_ms: K's length.
create perfetto table gs as
select o.i,
  e.ts as e_ts, e.dur as e_dur, c.ts as c_ts, g.ts as g_ts,
  k.ts as k_ts, k.dur as k_dur
from opens o
join zt e on e.id = (select x.id from zt x where x.name glob 'zl:sendEvent*' and x.ts >= o.u_ts and x.ts < o.w_end
  order by x.ts limit 1)
join zl_sl c on c.id = (select x.id from zl_sl x where x.name glob 'zl:composed*' and x.ts >= e.ts and x.ts < o.w_end
  order by x.ts limit 1)
left join zt g on g.name glob 'zl:sendFrame*' and c.ts >= g.ts and c.ts < g.ts + g.dur
left join main_sl k on k.id = (select x.id from main_sl x where x.name glob 'Choreographer#doFrame [0-9]*'
  and x.ts < g.ts order by x.ts desc limit 1);

-- One row per open and frame kind, ms from the UP (prelude's opens: every open, with or without a
-- reveal; a kind without its marker in the window has empty readings).
create perfetto table q as
select o.i, k.kind,
  (r.m_ts - o.u_ts) / 1e6 as marker,
  (r.f_ts - o.u_ts) / 1e6 as f_start,
  (r.f_ts + r.f_dur - o.u_ts) / 1e6 as f_end,
  r.rec_dur / 1e6 as rec_ms, r.ml_dur / 1e6 as ml_ms,
  (r.qs_end - o.u_ts) / 1e6 as q_same, r.ds_dur / 1e6 as ds_ms,
  (r.qw_end - o.u_ts) / 1e6 as q_drew, r.dw_dur / 1e6 as dw_ms,
  case when r.dw_vsync is null then null when r.dw_vsync = r.vsync then 1 else 0 end as drew_own,
  (r.q0_end - o.u_ts) / 1e6 as q_r0,
  (r.row_end - o.u_ts) / 1e6 as row_end,
  (r.sf_end - o.u_ts) / 1e6 as sf_end,
  (g.c_ts - g.e_ts) / 1e6 as ec,
  (g.g_ts - g.e_ts - g.e_dur) / 1e6 as e_wait,
  case when g.k_ts is null then null
    when g.k_ts = (select r2.f_ts from rd r2 where r2.i = o.i and r2.kind = 'reveal') then 1 else 0 end as k_reveal,
  g.k_dur / 1e6 as k_ms,
  -- Whole vsyncs from the grid point g0 (the last at or before the UP; both differences >= 0, so
  -- the casts are floors): the buffer's vsync interval minus the UP's.
  case when r.qw_end is null or r.g0 is null or (select p from vper) is null then null
    else cast((r.qw_end - r.g0) / (select p from vper) as int) - cast((r.u_ts - r.g0) / (select p from vper) as int)
  end as q_vs
from opens o
join (select 'reveal' as kind union all select 'drawn') k
left join rd r on r.i = o.i and r.kind = k.kind
left join gs g on g.i = o.i;
