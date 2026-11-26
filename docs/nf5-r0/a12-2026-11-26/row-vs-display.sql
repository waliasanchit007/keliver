-- Diagnostic (A12 session, 2026-11-26; not part of the rule's files; run as frame-end.sql is, after
-- the arm's prelude and measure): the app's actual frame timeline rows around opens 3-5's apply,
-- with their display frame and SurfaceFlinger's row for it (its end = the display frame's
-- present), ms relative to the apply's start. It shows that the app row's end is the frame's buffer
-- queued (about its queueBuffer), not the display's present. Its output was not kept; rerun it on
-- the kept trace.
create perfetto table sfu as select upid from process where name glob '*surfaceflinger*';
select h.i, f.name as vsync, round((f.ts - h.a_ts) / 1e6, 1) as app_start, round(f.dur / 1e6, 1) as app_dur,
  f.display_frame_token as dft, f.layer_name, f.present_type,
  (select round((d.ts - h.a_ts) / 1e6, 1) from actual_frame_timeline_slice d where d.upid in (select upid from sfu)
     and d.name = cast(f.display_frame_token as text)) as sf_start,
  (select round((d.ts + d.dur - h.a_ts) / 1e6, 1) from actual_frame_timeline_slice d where d.upid in (select upid from sfu)
     and d.name = cast(f.display_frame_token as text)) as sf_end,
  (select d.present_type from actual_frame_timeline_slice d where d.upid in (select upid from sfu)
     and d.name = cast(f.display_frame_token as text)) as sf_present_type
from host h join actual_frame_timeline_slice f on f.upid in (select upid from app_upid)
  and f.ts > h.a_ts - 20000000 and f.ts < h.a_ts + 50000000
where h.i in (3, 4, 5)
order by h.i, f.ts;
