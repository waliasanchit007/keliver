// The Chrome-start retry's own test (no Chrome): run.sh runs it before the suite.
//   B1 a start that opens its port first time is not retried, and logs nothing
//   B2 the run's first start gets the long wait; later starts the short one
//   B3 one miss is retried once, on a fresh profile with the long wait, and logged
//   B4 a second miss still fails, naming both misses
//   B5 a start that fails some other way (Chrome exited) is not retried
//   B6 a slow first start (over the short wait) is logged
import { NoPortError, startWithRetry } from './browser.mjs';

let failed = 0;
const check = (id, cond, what) => {
  console.log(`${cond ? 'PASS' : 'FAIL'} ${id}: ${what}`);
  if (!cond) failed++;
};
const opts = (first) => ({ first, waitMs: 16000, firstWaitMs: 45000 });

// A fake start: outcome i of [outcomes] for call i; records (dir, wait).
function fake(outcomes) {
  const calls = [];
  const start = async (dir, wait) => {
    calls.push({ dir, wait });
    const o = outcomes[calls.length - 1];
    if (o === 'miss') throw new NoPortError(`stderr ${calls.length}`);
    if (o === 'exit') throw new Error('Chrome exited (1)');
    return { proc: null, port: 9222, ms: o };
  };
  return { start, calls };
}

{
  const f = fake([300]); const logs = [];
  const r = await startWithRetry(f.start, '/p', { ...opts(false), log: (l) => logs.push(l) });
  check('B1', r.port === 9222 && f.calls.length === 1 && logs.length === 0, 'no retry, no log');
  check('B2', f.calls[0].wait === 16000, `a later start waits 16 s (${f.calls[0].wait})`);
}
{
  const f = fake([300]);
  await startWithRetry(f.start, '/p', { ...opts(true), log: () => {} });
  check('B2', f.calls[0].wait === 45000, `the first start waits 45 s (${f.calls[0].wait})`);
}
{
  const f = fake(['miss', 1200]); const logs = [];
  const r = await startWithRetry(f.start, '/p', { ...opts(true), log: (l) => logs.push(l) });
  check('B3', r.ms === 1200 && f.calls.length === 2 && f.calls[1].dir === '/p-retry' && f.calls[1].wait === 45000,
    `retried once on /p-retry with 45 s (${JSON.stringify(f.calls)})`);
  check('B3', logs.length === 2 && /retrying once/.test(logs[0]) && /first start/.test(logs[0]) && /retried Chrome/.test(logs[1]),
    `both logged: ${JSON.stringify(logs)}`);
}
{
  const f = fake(['miss', 'miss']);
  let err = null;
  try { await startWithRetry(f.start, '/p', { ...opts(false), log: () => {} }); } catch (e) { err = e; }
  check('B4', err != null && /twice/.test(err.message) && /stderr 1/.test(err.message) && /stderr 2/.test(err.message) && f.calls.length === 2,
    `fails after two misses: ${err?.message?.split('\n')[0]}`);
}
{
  const f = fake(['exit', 300]);
  let err = null;
  try { await startWithRetry(f.start, '/p', { ...opts(true), log: () => {} }); } catch (e) { err = e; }
  check('B5', err != null && /exited/.test(err.message) && f.calls.length === 1, 'an exit is not retried');
}
{
  const f = fake([20000]); const logs = [];
  await startWithRetry(f.start, '/p', { ...opts(true), log: (l) => logs.push(l) });
  check('B6', logs.length === 1 && /took 20000 ms/.test(logs[0]), `logged: ${JSON.stringify(logs)}`);
}

console.log(failed ? `browser-retry: ${failed} failed` : 'browser-retry: all passed');
process.exit(failed ? 1 : 0);
