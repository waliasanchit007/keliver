-- Diagnostic (A12 session, 2026-11-26; not part of the rule's files; run as frame-end.sql is, after
-- the arm's prelude and measure): around opens 3, 4, 5, 6 and 8's apply (host.a_ts), the main
-- thread's doFrames, the RenderThread's DrawFrames/queueBuffer, and the app's actual and expected
-- frame timeline rows, ms relative to the apply's start. It shows which row measure.sql matched
-- when j < 0 (a zero-length one) and which frame drew the detail.
create perfetto table dg as
select h.i, 'df' as kind, round((d.ts - h.a_ts) / 1e6, 1) as rel_start, round((d.ts + d.dur - h.a_ts) / 1e6, 1) as rel_end,
  d.name as name, null as present_type, null as jank, case when d.ts = h.df_ts then 'MATCHED' else '' end as note
from host h join main_sl d on d.name glob 'Choreographer#doFrame*' and d.ts > h.a_ts - 40000000 and d.ts < h.a_ts + 60000000
where h.i in (3, 4, 5, 6, 8)
union all
select h.i, 'rt', round((s.ts - h.a_ts) / 1e6, 1), round((s.ts + s.dur - h.a_ts) / 1e6, 1), s.name, null, null, ''
from host h join slice s on s.ts > h.a_ts - 40000000 and s.ts < h.a_ts + 60000000
join thread_track tt on s.track_id = tt.id join thread t on t.utid = tt.utid
where h.i in (3, 4, 5, 6, 8) and t.upid in (select upid from app_upid) and t.name = 'RenderThread'
  and (s.name glob 'DrawFrame*' or s.name glob 'queueBuffer*')
union all
select h.i, 'actual', round((f.ts - h.a_ts) / 1e6, 1), round((f.ts + f.dur - h.a_ts) / 1e6, 1), f.name, f.present_type, f.jank_type,
  case when f.ts + f.dur = h.present then 'PRESENT_USED' else '' end
from host h join actual_frame_timeline_slice f on f.upid in (select upid from app_upid)
  and f.ts > h.a_ts - 60000000 and f.ts < h.a_ts + 60000000
where h.i in (3, 4, 5, 6, 8)
union all
select h.i, 'expected', round((f.ts - h.a_ts) / 1e6, 1), round((f.ts + f.dur - h.a_ts) / 1e6, 1), f.name, null, null, ''
from host h join expected_frame_timeline_slice f on f.upid in (select upid from app_upid)
  and f.ts > h.a_ts - 60000000 and f.ts < h.a_ts + 60000000
where h.i in (3, 4, 5, 6, 8);
select * from dg order by i, rel_start;
