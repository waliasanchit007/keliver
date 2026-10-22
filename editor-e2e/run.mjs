#!/usr/bin/env node
// The editor end-to-end runner. Normally started by run.sh, which builds the
// editor and relay, copies the fixture and starts both servers; this file only
// runs tests against them. Each test gets a fresh Chrome on a fresh profile;
// a failing test leaves a screenshot, the page's console and a DOM summary in
// its artifacts directory.
//
//   E2E_EDITOR_URL   the served editor, e.g. http://127.0.0.1:8412   (required)
//   E2E_RELAY_PORT   the scratch relay's port                         (required)
//   E2E_FIXTURE_DIR  the fixture copy that relay serves               (required)
//   E2E_OUT          artifacts directory        (default: ./editor-e2e-out)
//   E2E_GREP         run only tests whose "id title" matches this regex
//   E2E_TEST_TIMEOUT per-test timeout in ms     (default: 120000)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { launch, sleep } from './lib/browser.mjs';
import { Editor, Relay } from './lib/editor.mjs';
import { snapshot, changedSources } from './lib/fixture.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const need = (k) => { const v = process.env[k]; if (!v) { console.error(`run.mjs: ${k} is not set (use run.sh)`); process.exit(2); } return v; };

if (typeof WebSocket === 'undefined') {
  console.error('run.mjs: this Node has no global WebSocket. Use Node 22+, or Node 20/21 with --experimental-websocket.');
  process.exit(2);
}

const editorUrl = need('E2E_EDITOR_URL').replace(/\/$/, '');
const relay = new Relay(+need('E2E_RELAY_PORT'));
const fixtureDir = path.resolve(need('E2E_FIXTURE_DIR'));
const out = path.resolve(process.env.E2E_OUT || 'editor-e2e-out');
const grep = process.env.E2E_GREP ? new RegExp(process.env.E2E_GREP, 'i') : null;
const testTimeout = +(process.env.E2E_TEST_TIMEOUT || 120000);
fs.mkdirSync(out, { recursive: true });

// Discover tests: every tests/*.test.mjs default-exports an array of { id, title, run(ctx) }.
const testDir = path.join(here, 'tests');
const tests = [];
for (const f of fs.readdirSync(testDir).filter((f) => f.endsWith('.test.mjs')).sort()) {
  const mod = await import(pathToFileURL(path.join(testDir, f)).href);
  for (const t of mod.default) tests.push({ ...t, file: f });
}
const selected = tests.filter((t) => !grep || grep.test(`${t.id} ${t.title}`));
if (!selected.length) { console.error('run.mjs: no test matches E2E_GREP'); process.exit(2); }

const pristine = snapshot(fixtureDir);
const slug = (t) => `${t.id}-${t.title}`.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 60);
const results = [];
const started = Date.now();

console.log(`editor-e2e: ${selected.length} test(s) against ${editorUrl} (relay :${relay.port})\n`);

for (const t of selected) {
  const dir = path.join(out, slug(t));
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  const logLines = [];
  const log = (msg) => { logLines.push(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${msg}`); };
  const t0 = Date.now();
  let page = null, error = null;
  try {
    page = await launch({ profileDir: path.join(out, 'profiles', slug(t)), artifacts: dir });
    const editor = new Editor(page, { url: editorUrl, relay });
    const ctx = { page, editor, relay, fixtureDir, artifacts: dir, log };
    let timer;
    await Promise.race([
      t.run(ctx),
      new Promise((_, rej) => { timer = setTimeout(() => rej(new Error(`test timed out after ${testTimeout} ms`)), testTimeout); }),
    ]).finally(() => clearTimeout(timer));
  } catch (e) {
    error = e;
  }
  // Evidence on failure: what the page looked like, said and selected.
  if (error && page) {
    try { await page.screenshot('failure'); } catch { /* page gone */ }
    try {
      const dom = await page.eval(`JSON.stringify({
        selected: [...document.querySelectorAll('.tree-row.selected')].map(r => r.textContent),
        propsHeader: document.querySelector('.pane.right .card')?.firstElementChild?.textContent ?? null,
        outline: [...document.querySelectorAll('.tree-row')].map(r => r.textContent),
      }, null, 2)`);
      fs.writeFileSync(path.join(dir, 'dom.json'), dom);
    } catch { /* page gone */ }
  }
  if (page) {
    const consoleText = [
      ...page.console.map((c) => `${c.type}: ${c.text}`),
      ...page.exceptions.map((e) => `EXCEPTION: ${e}`),
    ].join('\n');
    fs.writeFileSync(path.join(dir, 'console.log'), consoleText + '\n');
    await page.close();
  }
  // Every test must leave the fixture's sources as it found them (an edit is undone).
  const dirty = changedSources(pristine, snapshot(fixtureDir));
  if (dirty.length) {
    const msg = `the test left the fixture changed: ${dirty.map((d) => d.file).join(', ')}`;
    error = error ?? new Error(msg);
    log(msg);
    for (const d of dirty) {
      const p = path.join(fixtureDir, d.file);
      if (pristine[d.file] == null) fs.rmSync(p, { force: true }); else fs.writeFileSync(p, pristine[d.file]);
    }
    await sleep(1500); // let the relay re-ingest the restored files
  }
  const ms = Date.now() - t0;
  if (error) log(`FAILED: ${error.stack ?? error}`);
  fs.writeFileSync(path.join(dir, 'log.txt'), logLines.join('\n') + '\n');
  // A test marked `knownBug` documents a defect that is not fixed yet: its
  // failure is reported (XFAIL) but does not fail the run; once it passes
  // (XPASS) the run fails, so the mark is removed with the fix.
  const status = t.knownBug ? (error ? 'xfail' : 'xpass') : (error ? 'fail' : 'ok');
  results.push({ id: t.id, title: t.title, file: t.file, status, ok: status === 'ok' || status === 'xfail', ms,
    knownBug: t.knownBug ?? null, error: error ? String(error.message ?? error) : null, artifacts: dir });
  const label = { ok: 'ok   ', fail: 'FAIL ', xfail: 'xfail', xpass: 'XPASS' }[status];
  console.log(`${label} ${t.id.padEnd(6)} ${t.title}  (${(ms / 1000).toFixed(1)}s)`);
  if (t.knownBug) console.log(`      known bug: ${t.knownBug}${status === 'xpass' ? '\n      it passes now: remove its knownBug mark' : ''}`);
  if (error) console.log(`      ${String(error.message ?? error).split('\n').join('\n      ')}\n      artifacts: ${dir}`);
}

if (!process.env.E2E_KEEP_PROFILES) fs.rmSync(path.join(out, 'profiles'), { recursive: true, force: true });
const failed = results.filter((r) => !r.ok);
const total = Date.now() - started;
fs.writeFileSync(path.join(out, 'summary.json'), JSON.stringify({ editorUrl, total, results }, null, 2));
const xfail = results.filter((r) => r.status === 'xfail').length;
console.log(`\neditor-e2e: ${results.filter((r) => r.status === 'ok').length} passed, ${failed.length} failed` +
  `${xfail ? `, ${xfail} known bug(s)` : ''}, ${results.length} total (${(total / 1000).toFixed(1)}s)`);
process.exit(failed.length ? 1 : 0);
