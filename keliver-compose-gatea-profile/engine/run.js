// Runs the guest-profile workload (docs/GUEST_PROFILE.md) in a plain qjs, mirroring
// QuickJsProfile.kt: init, first, then (warmup + iterations) x [open:i%5, back].
// Usage: qjs --std engine/run.js <workload dir> <mode> <warmup> <iterations> <label>
import * as std from 'std';
const [, dir, mode = 'kotlinx', warmupS = '3', itersS = '30', label = '?'] = scriptArgs;
const warmup = +warmupS, iterations = +itersS;
const cache = {};
function req(name) {
  name = name.replace(/^\.\//, '');
  if (!name.endsWith('.js')) name += '.js';
  if (cache[name]) return cache[name].exports;
  const module = { exports: {} };
  cache[name] = module;
  const src = std.loadFile(dir + '/' + name);
  if (src === null) throw new Error('missing module ' + name);
  const fn = std.evalScript('(function (module, exports, require) {' + src + '\n})');
  fn(module, module.exports, req);
  return module.exports;
}
const t0 = Date.now();
req('guest-profile.js');
const loadMs = Date.now() - t0;
if (typeof globalThis.guestProfile !== 'function') throw new Error('guestProfile was not installed');
const cmd = (c) => globalThis.guestProfile(c);
const field = (s, f) => { const m = new RegExp('"' + f + '":"?(-?[0-9a-z.]+)').exec(s); return m ? m[1] : null; };
cmd('init:mode=' + mode + ',items=20');
let t = Date.now();
const first = cmd('first');
const firstMs = Date.now() - t;
const open = [], back = [];
let lastOpen = '', lastBack = '', loopStart = 0;
for (let i = 0; i < warmup + iterations; i++) {
  if (i === warmup) loopStart = Date.now();
  t = Date.now(); lastOpen = cmd('open:' + (i % 5)); const o = Date.now() - t;
  t = Date.now(); lastBack = cmd('back'); const b = Date.now() - t;
  if (i >= warmup) { open.push(o); back.push(b); }
}
const loopMs = Date.now() - loopStart;
cmd('close');
// The JVM harness's rule (QuickJsProfile.kt): the middle value, or the mean of the two middle values.
const med = (a) => { const s = a.slice().sort((x, y) => x - y), n = s.length; return n % 2 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2; };
const out = {
  label, mode, loadMs, firstMs,
  openMedianMs: med(open), backMedianMs: med(back),
  openMinMs: Math.min(...open), backMinMs: Math.min(...back),
  openPlusBackMeanMs: +(loopMs / iterations).toFixed(2),
  firstHash: field(first, 'hash'), openHash: field(lastOpen, 'hash'), backHash: field(lastBack, 'hash'),
  backBytes: field(lastBack, 'bytes'), backProperties: field(lastBack, 'properties'), backCreates: field(lastBack, 'creates'),
};
print('ENGINE_RUN ' + JSON.stringify(out));
