-- NF5 R0 prelude: the app's process, its main and Zipline (Treehouse) threads and their slices, the
-- tap groups, and per open the click event E, the frame F that composed the detail, and C, the
-- guest's `composed` report inside F. Needs an x=trace build (zl:* and msg * sections).
-- Every name pattern is a GLOB, never a LIKE: in trace_processor v45 (the benchmarks APK's), a LIKE
-- on a perfetto table combined with ORDER BY ... LIMIT returned a row that does not match
-- (`Choreographer#onVsync` for 'Choreographer#doFrame%'); GLOB returned the right one (emulator
-- dry run, 2026-11-24).

-- The app's process by its main thread: a process forked after the trace began keeps the name
-- zygote64 in the process table.
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

create perfetto table main_sl as
select s.* from slice s join thread_track tt on s.track_id = tt.id
where tt.utid in (select utid from main_th);

create perfetto table zl_sl as
select s.* from slice s join thread_track tt on s.track_id = tt.id
where tt.utid in (select utid from zl_th);

-- The guest's top-level calls: zl:sendEvent and zl:sendFrame. zl:composed (the bench's report,
-- made in the guest's apply phase) and zl:sendChanges n=<changes> nest inside a zl:sendFrame.
create perfetto table zt as
select id, ts, dur, name from zl_sl where depth = 0 and name glob 'zl:*';

-- Tap groups: a new group after more than 550 ms without a guest call. The driver waits 700 ms
-- after each screen is drawn before its next tap's DOWN, and inside one tap the longest quiet
-- stretch is the hold (100 or 400 ms): with the press on, the press's preparation ends long before
-- the UP of a 400 ms hold, so the 300 ms of revision 3 split every idle-arm open in two (found by
-- the emulator dry run, 2026-11-24).
create perfetto table zg as
with gaps as (select ts, ts - lag(ts + dur) over (order by ts) as gap from zt),
     starts as (select ts from gaps where gap is null or gap > 550000000)
select row_number() over (order by ts) as g, ts as g_start,
  coalesce(lead(ts) over (order by ts), (select max(ts + dur) from zt) + 1) as g_end
from starts;

-- bench=nav, n=14: by default the last 28 groups are 14 opens and 14 backs, an open first. If q0a
-- shows otherwise (a stray guest call after the last back shifts every open onto a back), list the
-- open groups' numbers in <arm>.opengroups.txt; analyse.sh puts them in place of OPENGROUPS (R0-6).
create perfetto table listed as
select row_number() over (order by g) as i, g from zg where g in (OPENGROUPS);

create perfetto table opens as
select i, g_start, g_end from listed join zg using (g)
union all
select (k + 1) / 2 as i, g_start, g_end from (
  select g_start, g_end, g - (select max(g) from zg) + 28 as k from zg)
where k >= 1 and k % 2 = 1 and (select count(*) from listed) = 0;

-- Per open: C = the group's last zl:composed (with the press on, the prepare frame's own report
-- comes earlier in the group); F = the zl:sendFrame that holds C; E = the last zl:sendEvent that
-- ends before F starts (the click).
create perfetto table step as
select o.i, c.ts as c_ts, f.ts as f_ts, f.dur as f_dur, e.ts as e_ts, e.dur as e_dur,
  (select max(x.ts + x.dur) from zl_sl x where x.name glob 'zl:sendChanges*'
     and x.ts >= f.ts and x.ts < f.ts + f.dur) as sc_end,
  (select x.name from zl_sl x where x.name glob 'zl:sendChanges*'
     and x.ts >= f.ts and x.ts < f.ts + f.dur order by x.ts desc limit 1) as sc_name
from opens o
join zl_sl c on c.id = (select c2.id from zl_sl c2 where c2.name = 'zl:composed'
  and c2.ts >= o.g_start and c2.ts < o.g_end order by c2.ts desc limit 1)
join zt f on f.name glob 'zl:sendFrame*' and c.ts >= f.ts and c.ts < f.ts + f.dur
join zt e on e.id = (select e2.id from zt e2 where e2.name glob 'zl:sendEvent*'
  and e2.ts >= o.g_start and e2.ts + e2.dur <= f.ts order by e2.ts desc limit 1);
