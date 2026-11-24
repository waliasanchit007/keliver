-- Q0b (after prelude.sql): the main thread's messages in the 200 ms before the click event E, for
-- opens 3 to 5. Find the driver's UP: the same name in every open, about 100 ms (the hold) after
-- the DOWN's message, and the last of that name before E. Put its exact name (with the leading
-- "msg ") in upname.txt; measure.sql's UPNAME is replaced by it.
select s.i, round((m.ts - s.e_ts) / 1e6, 1) as ms_before_event, round(m.dur / 1e6, 2) as dur_ms, m.name
from step s join main_sl m on m.depth = 0 and m.name glob 'msg *'
  and m.ts between s.e_ts - 200000000 and s.e_ts
where s.i in (3, 4, 5) order by s.i, m.ts;
