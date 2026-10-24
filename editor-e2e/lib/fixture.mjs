// The fixture app on disk: the relay writes edits back to these .kt files, so
// tests assert on them directly (what changed, and that an undo restores the
// file byte for byte).
import fs from 'node:fs';
import path from 'node:path';
import { sleep } from './browser.mjs';

/**
 * Files the relay generates beside a screen on every edit (Compiled_<screen>.kt,
 * see AUDIT E43). They are not the user's source, so "the source is unchanged"
 * ignores them.
 */
export const isGenerated = (rel) => path.basename(rel).startsWith('Compiled_');

/** { relativePath: contents } for every .kt and .json file under [dir]. */
export function snapshot(dir) {
  const out = {};
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.(kt|json)$/.test(e.name)) out[path.relative(dir, p)] = fs.readFileSync(p, 'utf8');
    }
  };
  walk(dir);
  return out;
}

/** Source files (not generated ones) that differ between two snapshots, with their changed lines. */
export function changedSources(before, after) {
  const files = new Set([...Object.keys(before), ...Object.keys(after)]);
  const out = [];
  for (const f of [...files].sort()) {
    if (isGenerated(f) || before[f] === after[f]) continue;
    out.push({ file: f, lines: changedLines(before[f] ?? '', after[f] ?? '') });
  }
  return out;
}

/** A line-level diff for small edits: the lines removed and added between the common head and tail. */
export function changedLines(a, b) {
  const x = a.split('\n'), y = b.split('\n');
  let head = 0;
  while (head < x.length && head < y.length && x[head] === y[head]) head++;
  let tail = 0;
  while (tail < x.length - head && tail < y.length - head && x[x.length - 1 - tail] === y[y.length - 1 - tail]) tail++;
  return { at: head + 1, removed: x.slice(head, x.length - tail), added: y.slice(head, y.length - tail) };
}

/** Waits until [file] satisfies [pred] (the relay writes back ~400 ms after an edit), then until it is stable. */
export async function waitForFile(file, pred, { timeout = 10000, message } = {}) {
  const end = Date.now() + timeout;
  let text = null;
  for (;;) {
    text = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
    if (pred(text)) break;
    if (Date.now() > end) throw new Error(message ?? `timed out waiting for ${file}`);
    await sleep(100);
  }
  await sleep(600);
  const settled = fs.readFileSync(file, 'utf8');
  return settled;
}

/**
 * Waits until [file] is byte-identical to [expected]; if it never is, fails
 * with [message] and the lines that still differ (what an undo left behind).
 */
export async function expectBytes(file, expected, message, { timeout = 10000 } = {}) {
  try {
    return await waitForFile(file, (t) => t === expected, { timeout, message });
  } catch (e) {
    const now = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
    const d = changedLines(expected, now);
    const show = (sign, lines) => (lines.length ? lines.map((l) => `\n    ${sign} ${l}`).join('') : `\n    ${sign} (nothing)`);
    throw new Error(`${message}\n    from line ${d.at}:${show('-', d.removed)}${show('+', d.added)}`);
  }
}
