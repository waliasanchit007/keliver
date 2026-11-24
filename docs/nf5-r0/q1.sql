-- Q1 (after prelude.sql and measure.sql): one row per open, ms, rounded.
select i, round(idle_at_up, 1) as idle,
  round(a_up_to_event, 1) as a, round(b_event, 1) as b, round(c_event_to_frame, 1) as c,
  round(c1_event_to_callback, 1) as c1, round(c2_callback_to_frame, 1) as c2,
  round(c_zl_running, 1) as c_zl_run, round(d_frame_to_composed, 1) as d,
  round(d_running, 1) as d_run, round(d_running_fast, 1) as d_run_fast,
  round(d_running_slow, 1) as d_run_slow,
  round(d_runnable, 1) as d_runnable, round(d_other, 1) as d_other,
  round(d_other_sleep, 1) as d_oth_s, round(d_other_d, 1) as d_oth_d, round(w_waits, 1) as w,
  round(e_composed_to_sent, 1) as e, round(f_sent_to_apply, 1) as f, round(g_apply, 1) as g,
  round(h_apply_to_frame, 1) as h, round(i_frame, 1) as i_frame, round(i_layout, 1) as i_layout,
  round(j_frame_to_present, 1) as j, round(total_up_to_present, 1) as total,
  round(check_lo, 1) as check_lo, round(check_hi, 1) as check_hi, sc_name, a_name
from q order by i;
