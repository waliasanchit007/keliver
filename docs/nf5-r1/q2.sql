-- Q2 (after prelude.sql and measure.sql): medians over opens 3 to 14 (the bench's warm-up of 2
-- dropped), each with n, the opens that had a value. `reveal.*`: the frame that drew the revealed
-- detail; `drawn.*`: the bench's `drawn` frame (native's detail, or the guest's live detail);
-- `guest.*`: the guest's step after the click (empty on native). Then the counts: opens in the
-- trace (`count.opens`, 14 expected), opens 3-14 with a reveal (`count.reveal`), with the
-- reveal's frame ticking the guest (`count.k_reveal`), and per kind the opens whose buffer was
-- queued by the frame's own vsync (`count.<kind>.drew_own`, in the median column, with how many had a
-- buffer in the n column) and by a RenderThread-only frame (`count.<kind>.q_rt`, measure.sql's
-- q_src), and the app's vsync period that `q_vs` counts with (`vsync.period_ms`). A median of an
-- even n is the mean of the two middle values, as R0's q2.
create perfetto table qq as select * from q where i > 2;

create perfetto table lng as
select kind || '.marker' as col, marker as v from qq
union all select kind || '.f_start', f_start from qq
union all select kind || '.f_end', f_end from qq
union all select kind || '.rec_ms', rec_ms from qq
union all select kind || '.ml_ms', ml_ms from qq
union all select kind || '.q_same', q_same from qq
union all select kind || '.q_drew', q_drew from qq
union all select kind || '.dw_ms', dw_ms from qq
union all select kind || '.q_r0', q_r0 from qq
union all select kind || '.row_end', row_end from qq
union all select kind || '.sf_end', sf_end from qq
union all select kind || '.q_vs', q_vs from qq
union all select 'guest.ec', ec from qq where kind = 'drawn'
union all select 'guest.e_wait', e_wait from qq where kind = 'drawn'
union all select 'guest.k_ms', k_ms from qq where kind = 'drawn';

select col, median, n from (
  select 1 as grp, col, round(avg(v), 1) as median, max(n) as n from (
    select col, v, row_number() over (partition by col order by v) as rn,
      count(*) over (partition by col) as n from lng where v is not null)
  where rn in ((n + 1) / 2, (n + 2) / 2) group by col
  union all
  select 2, 'vsync.period_ms', (select round(p / 1e6, 2) from vper), null
  union all
  select 2, 'count.opens', (select count(*) from opens), null
  union all
  select 2, 'count.reveal', (select count(*) from qq where kind = 'reveal' and marker is not null), null
  union all
  select 2, 'count.k_reveal', (select count(*) from qq where kind = 'drawn' and k_reveal = 1), null
  union all
  select 2, 'count.' || kind || '.drew_own', sum(drew_own), count(drew_own) from qq group by kind
  union all
  select 2, 'count.' || kind || '.q_rt', sum(case when q_src = 'rt' then 1 else 0 end), count(q_src) from qq group by kind)
order by grp, col;
