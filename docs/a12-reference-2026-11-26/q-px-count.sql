-- Series K, the mapping check (after q-px.sql's tables): per touch, the app's frames presented in
-- the window (DOWN, next DOWN - 100 ms) against the raw frames composed in (DOWN, next DOWN - 100 ms),
-- and the last frame's present against the last raw frame's q (the recorder's lag at the end).
create perfetto table appf as
select m.ts, cast(substr(m.name, length('Choreographer#doFrame ') + 1) as int) as vsync
from ms m where m.name like 'Choreographer#doFrame%';
create perfetto table win as
select d.k, d.down, coalesce((select min(d2.down) from downs d2 where d2.down > d.down), d.down + 2500000000) - 100000000 as wend
from downs d;
select w.k,
  case (w.k - 1) % 3 when 0 then 'row' when 1 then 'back' else 'load' end as action,
  (select count(*) from appf a where a.ts > w.down and a.ts < w.wend) as app_frames,
  (select count(*) from fr2 f where f.q > w.down and f.q < w.wend) as raw_frames,
  (select count(*) from fr2 f where f.q > w.down and f.q < w.wend and f.n > 0) as raw_changed,
  round(((select max(f.q) from fr2 f where f.q > w.down and f.q < w.wend)
    - (select max(x.ts + x.dur) from actual_frame_timeline_slice x where x.upid in (select upid from app_main)
       and x.name = cast((select a.vsync from appf a where a.ts > w.down and a.ts < w.wend order by a.ts desc limit 1) as text))) / 1e6, 1) as last_lag,
  round(((select min(f.q) from fr2 f where f.q > w.down and f.q < w.wend)
    - (select max(x.ts + x.dur) from actual_frame_timeline_slice x where x.upid in (select upid from app_main)
       and x.name = cast((select a.vsync from appf a where a.ts > w.down and a.ts < w.wend order by a.ts limit 1) as text))) / 1e6, 1) as first_lag,
  (select count(*) from fr2 f where f.q > w.down - 1000000000 and f.q < w.down) as raw_before
from win w order by w.k;
