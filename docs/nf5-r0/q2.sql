-- Q2 (after prelude.sql and measure.sql): medians over opens 3 to 14 (the bench's warm-up of 2
-- dropped; on simple-on-idle only the opens whose guest was idle at the UP: measure.sql's qq, R-L1;
-- `n` says how many). W_waits = (c - c_zl_running) + d_runnable + d_other (R0-3). G = d_running,
-- all of it, on any CPU at any frequency (R-M1); d_running_fast and d_running_slow are information
-- only. The projection (R0-4), from the UP, as native's times are: P = total_up_to_present -
-- W_removable + the rebind's extra guest work (appendix B: 14 ms at a ratio of 12, 36 at 30). The
-- P_* rows take ALL of W as removable, per open: the rule then subtracts only the parts with a
-- named mechanism (appendix D). After the medians, one row per CPU (R-M1): `cpuN fast`, `cpuN slow`
-- or `cpuN unknown`, with the highest frequency its cpufreq track recorded, kHz (empty: no track).
create perfetto table lng as
select 'idle_at_up' as col, idle_at_up as v from qq
union all select 'a_up_to_event', a_up_to_event from qq
union all select 'b_event', b_event from qq
union all select 'c_event_to_frame', c_event_to_frame from qq
union all select 'c1_event_to_callback', c1_event_to_callback from qq
union all select 'c2_callback_to_frame', c2_callback_to_frame from qq
union all select 'c_zl_running', c_zl_running from qq
union all select 'd_frame_to_composed', d_frame_to_composed from qq
union all select 'd_running', d_running from qq
union all select 'd_running_fast', d_running_fast from qq
union all select 'd_running_slow', d_running_slow from qq
union all select 'd_runnable', d_runnable from qq
union all select 'd_other', d_other from qq
union all select 'd_other_sleep', d_other_sleep from qq
union all select 'd_other_d', d_other_d from qq
union all select 'e_composed_to_sent', e_composed_to_sent from qq
union all select 'f_sent_to_apply', f_sent_to_apply from qq
union all select 'g_apply', g_apply from qq
union all select 'h_apply_to_frame', h_apply_to_frame from qq
union all select 'i_frame', i_frame from qq
union all select 'i_layout', i_layout from qq
union all select 'j_frame_to_present', j_frame_to_present from qq
union all select 'total_up_to_present', total_up_to_present from qq
union all select 'W_waits', w_waits from qq
union all select 'P_allW_r12', total_up_to_present - w_waits + 14 from qq
union all select 'P_allW_r30', total_up_to_present - w_waits + 36 from qq;

select col, median_ms, n from (
  select 1 as grp, 0 as k, col, round(avg(v), 1) as median_ms, max(n) as n from (
    select col, v, row_number() over (partition by col order by v) as rn,
      count(*) over (partition by col) as n from lng where v is not null)
  where rn in ((n + 1) / 2, (n + 2) / 2) group by col
  union all
  select 2, cpu, 'cpu' || cpu || (case when max_khz is null then ' unknown'
      when cpu in (select cpu from cpu_fast) then ' fast' else ' slow' end), max_khz, null
  from cpu_max)
order by grp, k, col;
