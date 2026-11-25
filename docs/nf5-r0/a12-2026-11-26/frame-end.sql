-- Supplement to measure.sql (written during the A12 session, 2026-11-26; NOT one of the rule's
-- files, and analyse.sh does not run it). Run after the arm's prelude and measure, as analyse.sh
-- writes them, on the phone in /data/local/tmp/nf5floor:
--   cat <arm>.p.sql <arm>.m.sql a12-2026-11-26/frame-end.sql > fx.sql
--   ./trace_processor_shell -q fx.sql /data/misc/perfetto-traces/nf5-<arm>.pftrace
-- (THREE_WAY_BENCHMARK.md, "NF5 R0"). The frame that actually drew the detail. measure.sql takes the first main-thread doFrame
-- after the apply and that vsync's app frame-timeline row. On the A12 the press's ripple runs on the
-- RenderThread (RenderNodeAnimator) after the UP and queues its own buffer for that vsync, so the
-- doFrame's DrawFrames is skipped (< 1 ms) and the detail is drawn one vsync later; the row of the
-- skipped vsync has dur 0 (its end = its start), so j < 0 and `total` is too short.
-- Here: R = the first RenderThread `DrawFrames <vsync>` that starts at or after the matched doFrame
-- and lasts >= 2 ms (the draw of the new content); Q = the end of the first `queueBuffer` that starts
-- inside R (the buffer with the detail queued); row_end = that vsync's app actual frame-timeline
-- row's end when its dur > 0. T_q = Q - UP (frame end, as the bench's other frame-end numbers).
create perfetto table rt_th as
select utid from thread where upid in (select upid from app_upid) and name = 'RenderThread';
create perfetto table rt_sl as
select s.* from slice s join thread_track tt on s.track_id = tt.id where tt.utid in (select utid from rt_th);
create perfetto table fx as
select q.i, q.total_up_to_present as total_old, q.j_frame_to_present as j_old, q.idle_at_up,
  u.u_ts, h.df_ts,
  (select d.name from main_sl d where d.ts = h.df_ts and d.name glob 'Choreographer#doFrame*') as df_name,
  r.ts as r_ts, r.dur as r_dur, r.name as r_name,
  (select max(b.ts + b.dur) from rt_sl b where b.name = 'queueBuffer'
     and b.ts = (select min(b2.ts) from rt_sl b2 where b2.name = 'queueBuffer' and b2.ts >= r.ts and b2.ts < r.ts + r.dur)) as q_end,
  (select max(f.ts + f.dur) from actual_frame_timeline_slice f where f.upid in (select upid from app_upid)
     and f.name = substr(r.name, 12) and f.dur > 0) as row_end
from q join up u using (i) join host h using (i)
join rt_sl r on r.id = (select r2.id from rt_sl r2 where r2.name glob 'DrawFrames *' and r2.ts >= h.df_ts
  and r2.dur >= 2000000 order by r2.ts limit 1);
select i, round(idle_at_up, 1) as idle, df_name, r_name, round((r_ts - df_ts) / 1e6, 1) as r_after_df,
  round(r_dur / 1e6, 1) as r_ms, round(total_old, 1) as total_old, round(j_old, 1) as j_old,
  round((q_end - u_ts) / 1e6, 1) as t_q, round((row_end - u_ts) / 1e6, 1) as t_row,
  round((q_end - u_ts) / 1e6 - total_old, 1) as t_q_minus_old
from fx order by i;
