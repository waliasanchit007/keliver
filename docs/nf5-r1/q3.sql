-- Q3 (after prelude.sql and measure.sql), diagnostic: EVERY open, from 20 ms before its DOWN (the
-- driver's `bench:down open <i>`; the main thread then sleeps through the 40 ms hold) to 150 ms after
-- its UP: the main thread's doFrames and the driver's and the reveal's markers, the RenderThread's
-- DrawFrames and queueBuffer, and the app's actual frame-timeline rows, ms from the UP. It shows,
-- open by open, which frame drew the reveal and the detail, and whether a RenderThread-only buffer
-- (a queueBuffer outside any DrawFrames: a RenderThread animation, R0's ripple) took the vsync the
-- first frame after the UP needed. q4 counts the same per open.
create perfetto table dw as
select o.i, o.u_ts,
  coalesce((select max(m.ts) from main_sl m where m.name glob 'bench:down open *' and m.ts < o.u_ts
     and m.ts > o.u_ts - 1000000000), o.u_ts - 50000000) - 20000000 as w0
from opens o;

create perfetto table dg as
select o.i, 'main' as track, round((m.ts - o.u_ts) / 1e6, 1) as rel_start, round((m.ts + m.dur - o.u_ts) / 1e6, 1) as rel_end,
  m.name as name, null as present_type
from dw o join main_sl m on m.ts >= o.w0 and m.ts < o.u_ts + 150000000
  and (m.name glob 'Choreographer#doFrame*' or m.name glob 'bench:*' or m.name glob 'r1:*'
    or m.name = 'Record View#draw()')
union all
select o.i, 'render', round((s.ts - o.u_ts) / 1e6, 1), round((s.ts + s.dur - o.u_ts) / 1e6, 1), s.name, null
from dw o join rt_sl s on s.ts >= o.w0 and s.ts < o.u_ts + 150000000
  and (s.name glob 'DrawFrame*' or s.name = 'queueBuffer')
union all
select o.i, 'row', round((f.ts - o.u_ts) / 1e6, 1), round((f.ts + f.dur - o.u_ts) / 1e6, 1), f.name, f.present_type
from dw o join actual_frame_timeline_slice f on f.upid in (select upid from app_upid)
  and f.ts >= o.w0 and f.ts < o.u_ts + 150000000;
select * from dg order by i, rel_start, track;
