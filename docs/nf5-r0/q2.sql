-- Q2 (after prelude.sql and measure.sql): medians over opens 3 to 14 (the bench's warm-up of 2
-- dropped). W = the waits inside click -> composed: c + d_runnable + d_other.
create perfetto table lng as
select 'a_up_to_event' as col, a_up_to_event as v from q where i > 2
union all select 'b_event', b_event from q where i > 2
union all select 'c_event_to_frame', c_event_to_frame from q where i > 2
union all select 'c_zl_running', c_zl_running from q where i > 2
union all select 'd_frame_to_composed', d_frame_to_composed from q where i > 2
union all select 'd_running', d_running from q where i > 2
union all select 'd_running_fast', d_running_fast from q where i > 2
union all select 'd_runnable', d_runnable from q where i > 2
union all select 'd_other', d_other from q where i > 2
union all select 'e_composed_to_sent', e_composed_to_sent from q where i > 2
union all select 'f_sent_to_apply', f_sent_to_apply from q where i > 2
union all select 'g_apply', g_apply from q where i > 2
union all select 'h_apply_to_frame', h_apply_to_frame from q where i > 2
union all select 'i_frame', i_frame from q where i > 2
union all select 'i_layout', i_layout from q where i > 2
union all select 'j_frame_to_present', j_frame_to_present from q where i > 2
union all select 'total_up_to_present', total_up_to_present from q where i > 2
union all select 'W_waits', c_event_to_frame + d_runnable + d_other from q where i > 2;

select col, round(avg(v), 1) as median_ms, max(n) as n from (
  select col, v, row_number() over (partition by col order by v) as rn,
    count(*) over (partition by col) as n from lng)
where rn in ((n + 1) / 2, (n + 2) / 2) group by col order by col;
