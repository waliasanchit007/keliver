-- Series K (pixel check). Run after px-diff.sh's inserts (table px). Raw frame i is screenrecord's
-- i-th acquireBuffer; its composition time q is the virtual display's last queueBuffer before that
-- acquire; its present is the end of SurfaceFlinger's actual display frame that spans q.
-- 2026-11-26: the recorder's process by its thread's name (in one trace the process kept the name
-- of the shell it was forked from, `sh`, and matching the process name found nothing).
create perfetto table srp as
select distinct upid from thread where name = 'screenrecord' and upid is not null;
create perfetto table acq as
select row_number() over (order by s.ts) - 1 as i, s.ts as acq
from slice s join thread_track tt on s.track_id = tt.id join thread t using (utid)
where t.upid in (select upid from srp) and t.name = 'screenrecord' and s.name = 'acquireBuffer';
create perfetto table qb as
select s.ts from slice s join thread_track tt on s.track_id = tt.id join thread t using (utid)
where t.upid in (select upid from srp) and s.name = 'queueBuffer';
create perfetto table sfp as
select upid from process where name like '%surfaceflinger%';
create perfetto table fr as
select a.i, a.acq, (select max(q.ts) from qb q where q.ts <= a.acq) as q, p.n, p.x0, p.x1, p.y0, p.y1, p.maxd, p.sb
from acq a left join px p using (i);
create perfetto table fr2 as
select f.*, (select min(d.ts + d.dur) from actual_frame_timeline_slice d
  where d.upid in (select upid from sfp) and d.ts <= f.q and d.ts + d.dur >= f.q) as present
from fr f;
create perfetto table app_main as
select t.utid, t.upid from thread t
where t.name like '%keliver.sample' and exists (select 1 from thread_track tt join slice s on s.track_id = tt.id
  where tt.utid = t.utid and s.name like 'Choreographer#doFrame%');
create perfetto table ms as
select s.ts, s.dur, s.name from slice s join thread_track tt on s.track_id = tt.id
where tt.utid in (select utid from app_main);
create perfetto table inj as
select s.ts from slice s where s.name = 'interceptMotionBeforeQueueing';
create perfetto table downs as
select row_number() over (order by i.ts) as k, i.ts as down
from inj i where not exists (select 1 from inj j where j.ts < i.ts and j.ts > i.ts - 1500000000);
create perfetto table r as
select d.k, d.down,
  (select min(m.ts) from ms m where m.name like 'Choreographer#doFrame%' and m.ts > d.down) as fts,
  (select min(f.i) from fr2 f where f.q > d.down and f.q < d.down + 1500000000) as first_i,
  (select min(f.i) from fr2 f where f.q > d.down and f.q < d.down + 1500000000 and f.n > 0) as vis_i,
  (select min(f.i) from fr2 f where f.q > d.down and f.q < d.down + 1500000000 and f.maxd >= 8) as vis8_i,
  (select count(*) from fr2 f where f.q > d.down - 1000000000 and f.q < d.down and f.n > 0) as changed_before,
  (select count(*) from ms m where m.name like 'Choreographer#doFrame%' and m.ts > d.down - 1000000000 and m.ts < d.down) as frames_before
from downs d;
create perfetto table summary as
select r.k,
  case (r.k - 1) % 3 when 0 then 'row' when 1 then 'back' else 'load' end as action,
  round(((select max(a.ts + a.dur) from actual_frame_timeline_slice a join ms f on f.ts = r.fts
    where a.upid in (select upid from app_main)
      and a.name = substr(f.name, length('Choreographer#doFrame ') + 1)) - r.down) / 1e6, 1) as app_first_present,
  r.first_i, round((f1.present - r.down) / 1e6, 1) as first_present, f1.n as first_n,
  r.vis_i - r.first_i as vis_j, round((fv.q - r.down) / 1e6, 1) as vis_q, round((fv.present - r.down) / 1e6, 1) as vis_present,
  fv.n as vis_n, fv.x0, fv.x1, fv.y0, fv.y1, fv.maxd,
  r.vis8_i - r.first_i as vis8_j, round((f8.present - r.down) / 1e6, 1) as vis8_present, f8.n as vis8_n, f8.y0 as vis8_y0, f8.y1 as vis8_y1,
  (select group_concat(g.n || ':' || g.maxd || ':' || g.y0 || '-' || g.y1, ' ') from fr2 g where g.i between r.first_i and r.first_i + 5) as seq,
  r.changed_before, r.frames_before
from r
left join fr2 f1 on f1.i = r.first_i
left join fr2 fv on fv.i = r.vis_i
left join fr2 f8 on f8.i = r.vis8_i
order by r.k;
-- The summary is printed by q-px-main.sql (select * from summary); q-px-seq.sql prints sequences.
