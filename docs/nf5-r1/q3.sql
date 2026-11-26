-- Q3 (after prelude.sql and measure.sql), diagnostic: opens 3, 4 and 5, from 10 ms before the UP
-- to 150 ms after it: the main thread's doFrames and the driver's and the reveal's markers, the
-- RenderThread's DrawFrames and queueBuffer, and the app's actual frame-timeline rows, ms from the
-- UP. It shows, open by open, which frame drew the reveal and the detail, and whether the press's
-- ripple (RenderThread frames with no main-thread frame, R0) moved the draw to a later vsync.
create perfetto table dg as
select o.i, 'main' as track, round((m.ts - o.u_ts) / 1e6, 1) as rel_start, round((m.ts + m.dur - o.u_ts) / 1e6, 1) as rel_end,
  m.name as name, null as present_type
from opens o join main_sl m on m.ts > o.u_ts - 10000000 and m.ts < o.u_ts + 150000000
  and (m.name glob 'Choreographer#doFrame*' or m.name glob 'bench:*' or m.name glob 'r1:*'
    or m.name = 'Record View#draw()')
where o.i in (3, 4, 5)
union all
select o.i, 'render', round((s.ts - o.u_ts) / 1e6, 1), round((s.ts + s.dur - o.u_ts) / 1e6, 1), s.name, null
from opens o join rt_sl s on s.ts > o.u_ts - 10000000 and s.ts < o.u_ts + 150000000
  and (s.name glob 'DrawFrame*' or s.name = 'queueBuffer')
where o.i in (3, 4, 5)
union all
select o.i, 'row', round((f.ts - o.u_ts) / 1e6, 1), round((f.ts + f.dur - o.u_ts) / 1e6, 1), f.name, f.present_type
from opens o join actual_frame_timeline_slice f on f.upid in (select upid from app_upid)
  and f.ts > o.u_ts - 10000000 and f.ts < o.u_ts + 150000000
where o.i in (3, 4, 5);
select * from dg order by i, rel_start, track;
