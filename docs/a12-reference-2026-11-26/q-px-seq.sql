-- Series K: per touch, the first 4 app frames (doFrame start, present; vsync id) and the first 4 raw
-- frames (queueBuffer q, acquire, changed bytes), ms after the DOWN. Appended after q-px.sql.
create perfetto table appf as
select m.ts, cast(substr(m.name, length('Choreographer#doFrame ') + 1) as int) as vsync
from ms m where m.name like 'Choreographer#doFrame%';
create perfetto table appf2 as
select f.ts, f.vsync, (select max(a.ts + a.dur) from actual_frame_timeline_slice a
  where a.upid in (select upid from app_main) and a.name = cast(f.vsync as text)) as present,
  (select min(a.ts) from actual_frame_timeline_slice a
  where a.upid in (select upid from app_main) and a.name = cast(f.vsync as text)) as aft_start
from appf f;
select d.k,
  (select group_concat(round((x.ts - d.down) / 1e6, 1) || '>' || round((x.present - d.down) / 1e6, 1), ' ')
   from (select * from appf2 a where a.ts > d.down order by a.ts limit 4) x) as app_start_present,
  (select group_concat(round((y.q - d.down) / 1e6, 1) || '/' || round((y.acq - d.down) / 1e6, 1) || ':' || y.n, ' ')
   from (select * from fr2 f where f.q > d.down order by f.i limit 4) y) as raw_q_acq_n
from downs d order by d.k;
