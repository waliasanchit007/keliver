-- Q1 (after prelude.sql and measure.sql): one row per open, ms, rounded.
select i, round(a_up_to_event, 1) as a, round(b_event, 1) as b, round(c_event_to_frame, 1) as c,
  round(c_zl_running, 1) as c_zl_run, round(d_frame_to_composed, 1) as d,
  round(d_running, 1) as d_run, round(d_running_fast, 1) as d_run_fast,
  round(d_runnable, 1) as d_runnable, round(d_other, 1) as d_other,
  round(e_composed_to_sent, 1) as e, round(f_sent_to_apply, 1) as f, round(g_apply, 1) as g,
  round(h_apply_to_frame, 1) as h, round(i_frame, 1) as i_frame, round(i_layout, 1) as i_layout,
  round(j_frame_to_present, 1) as j, round(total_up_to_present, 1) as total,
  round(check_lo, 1) as check_lo, round(check_hi, 1) as check_hi, sc_name, a_name
from q order by i;
