-- NF5 R1 prelude (docs/nf5-r1/README.md): the app's process, its main, Zipline (Treehouse) and
-- RenderThread threads and their slices, and one row per open from the driver's own trace sections.
-- Needs an x=trace build: the driver's `bench:up <step> <i>` (its start is the tap's time, the UP),
-- `bench:drawn <screen> <seq>` (the window draw the bench takes as `drawn`), the reveal's markers
-- `r1:reveal`, `r1:revealDrawn` (inside the `Record View#draw()` that recorded the revealed detail)
-- and `r1:revealLive`, and the guest's `zl:*` calls.
-- As R0's (docs/nf5-r0/prelude.sql): every name pattern is a GLOB, never a LIKE (trace_processor v45
-- returned a non-matching row for a LIKE with ORDER BY ... LIMIT); the app's process is found by its
-- main thread (a process forked after the trace began keeps the name zygote64).

create perfetto table app_upid as
select upid from thread
where name = '.keliver.sample' and upid is not null
  and tid = (select pid from process p where p.upid = thread.upid)
order by (select count(*) from sched s where s.utid = thread.utid) desc limit 1;

create perfetto table main_th as
select utid from thread where upid in (select upid from app_upid)
  and tid = (select pid from process where upid in (select upid from app_upid));

create perfetto table zl_th as
select utid from thread where upid in (select upid from app_upid) and name glob 'Treehouse*'
order by (select count(*) from sched s where s.utid = thread.utid) desc limit 1;

create perfetto table rt_th as
select utid from thread where upid in (select upid from app_upid) and name = 'RenderThread';

create perfetto table main_sl as
select s.* from slice s join thread_track tt on s.track_id = tt.id
where tt.utid in (select utid from main_th);

create perfetto table zl_sl as
select s.* from slice s join thread_track tt on s.track_id = tt.id
where tt.utid in (select utid from zl_th);

create perfetto table rt_sl as
select s.* from slice s join thread_track tt on s.track_id = tt.id
where tt.utid in (select utid from rt_th);

-- The guest's top-level calls (zl:sendEvent, zl:sendFrame, zl:composed when the guest reports from
-- outside a frame); zl:composed and zl:sendChanges nest inside a zl:sendFrame.
create perfetto table zt as
select id, ts, dur, name from zl_sl where depth = 0 and name glob 'zl:*';

-- The driver's taps, in order: `bench:up open <i>` and `bench:up back <i>` ('bench:up open ' is 14
-- characters). Each open's window runs from its UP to the next tap's UP (its back), or the trace's end.
create perfetto table ups as
select ts as u_ts, case when name glob 'bench:up open *' then 'open' else 'back' end as step,
  cast(substr(name, 15) as int) as i
from main_sl where name glob 'bench:up open *' or name glob 'bench:up back *';

create perfetto table opens as
select u.i, u.u_ts,
  coalesce((select min(b.u_ts) from ups b where b.u_ts > u.u_ts), (select max(ts + dur) from main_sl)) as w_end
from ups u where u.step = 'open';

-- Per open, the two frames R1 reads, each found by its marker in the open's window:
-- `reveal`: the first r1:revealDrawn (the frame that drew the revealed detail; none without a reveal);
-- `drawn`: the first `bench:drawn detail *` (the bench's `drawn`: native's detail, or the guest's
-- live detail). F = the main thread's Choreographer#doFrame that holds the marker.
create perfetto table mk as
select o.i, o.u_ts, 'reveal' as kind,
  (select m.ts from main_sl m where m.name = 'r1:revealDrawn' and m.ts >= o.u_ts and m.ts < o.w_end
   order by m.ts limit 1) as m_ts
from opens o
union all
select o.i, o.u_ts, 'drawn',
  (select m.ts from main_sl m where m.name glob 'bench:drawn detail *' and m.ts >= o.u_ts and m.ts < o.w_end
   order by m.ts limit 1)
from opens o;

-- The frame's own slice is `Choreographer#doFrame <vsync id>`; the RenderThread's `DrawFrames <id>`
-- and the app's frame-timeline row carry the same id. A late frame on Android 13 or later also has a
-- child `Choreographer#doFrame - resynced to <id> in <ms>` with the same start: it is never taken
-- (the emulator dry run, 2026-11-26: taking it gave a vsync with no DrawFrames and no row).
create perfetto table fr as
select k.i, k.u_ts, k.kind, k.m_ts, f.ts as f_ts, f.dur as f_dur, f.name as f_name,
  cast(substr(f.name, 23) as int) as vsync
from mk k join main_sl f on f.id = (select f2.id from main_sl f2
  where f2.name glob 'Choreographer#doFrame [0-9]*' and f2.ts <= k.m_ts and f2.ts + f2.dur >= k.m_ts
  order by f2.ts desc limit 1)
where k.m_ts is not null;
