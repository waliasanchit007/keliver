-- Q0a (after prelude.sql): every tap group, to check the grouping. Expect the list's start
-- group(s), then 28 groups alternating open / back. With the press on, an open group has 2 events
-- (the press, the click) and 2 composed reports; with it off, 1 and 1. A back has 1 and 1.
select g, round((g_start - (select min(g_start) from zg)) / 1e6) as start_ms,
  round((g_end - g_start) / 1e6) as until_next_ms,
  (select count(*) from zt where ts >= g_start and ts < g_end and name glob 'zl:sendEvent*') as events,
  (select count(*) from zt where ts >= g_start and ts < g_end and name glob 'zl:sendFrame*') as frames,
  (select count(*) from zl_sl where ts >= g_start and ts < g_end and name = 'zl:composed') as composed,
  (select count(*) from opens o where o.g_start = zg.g_start) as is_open
from zg order by g;
