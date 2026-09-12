// U19 reconciliation: measure what the HOST does, per variant.
// Usage: node measure.mjs <devtools-port> <label> <url> <out-dir>
import fs from "node:fs";
const port = process.argv[2], label = process.argv[3], url = process.argv[4], out = process.argv[5];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const lines = [];
const say = (m) => { console.log(`[${label}] ${m}`); lines.push(m); };

const targets = await fetch(`http://127.0.0.1:${port}/json`).then((r) => r.json());
const page = targets.find((t) => t.type === "page");
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { socket.addEventListener("open", res, { once: true }); socket.addEventListener("error", rej, { once: true }); });
let nextId = 0; const pending = new Map();
socket.addEventListener("message", (e) => {
  const m = JSON.parse(e.data); if (!m.id || !pending.has(m.id)) return;
  const { resolve, reject } = pending.get(m.id); pending.delete(m.id);
  if (m.error) reject(new Error(JSON.stringify(m.error))); else resolve(m.result);
});
const cmd = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId; pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params }));
});
const ev = async (expr) => {
  const r = await cmd("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 400));
  return r.result.value;
};

await cmd("Page.enable"); await cmd("Runtime.enable");
await cmd("Emulation.setDeviceMetricsOverride", { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
await cmd("Page.navigate", { url }); await sleep(1000);
await ev(`localStorage.clear(); true`);
await cmd("Page.navigate", { url }); await sleep(1500);
await ev(`new Promise((res, rej) => { const d = Date.now()+60000; const p = () => {
  const c = [...document.querySelectorAll("canvas")].find(x => x.width > 0);
  if (c) res(true); else if (Date.now() > d) rej(new Error("no canvas")); else setTimeout(p, 100); }; p(); })`);

// --- provenance ------------------------------------------------------------
const provenance = await ev(`JSON.stringify({
  serviceWorkers: "unknown",
  caches: "unknown",
  resources: performance.getEntriesByType("resource")
    .filter(e => /\\.(wasm|js)$/.test(e.name))
    .map(e => ({ name: e.name.split("/").slice(-1)[0], size: e.transferSize, from: e.deliveryType || "network" })),
  probe: typeof globalThis.__probe,
})`);
const sw = await ev(`navigator.serviceWorker ? navigator.serviceWorker.getRegistrations().then(r => r.length) : -1`);
const cacheKeys = await ev(`(typeof caches !== "undefined") ? caches.keys().then(k => JSON.stringify(k)) : "[]"`);
say(`provenance ${provenance}`);
say(`serviceWorkers=${sw} cacheStorage=${cacheKeys}`);

const probe = () => ev(`JSON.stringify(globalThis.__probe || null)`).then((s) => JSON.parse(s));
const panels = () => ev(`(() => { const t=document.body.innerText; const i=t.indexOf("STATE INSPECTOR");
  const j=t.indexOf("ACTION CONSOLE", i); return t.slice(i+15, j).trim().replace(/\\n/g," | "); })()`);
const val = async (key) => {
  const t = await panels(); const m = t.match(new RegExp(`${key}\\s*=\\s*"([^"]*)"`)); return m ? m[1] : null;
};

const results = { label, provenance: JSON.parse(provenance), serviceWorkers: sw, cacheStorage: JSON.parse(cacheKeys) };

// Frame accounting over a quiet window: NO evaluate calls inside the window.
const quiet = async (name, ms) => {
  const a = await probe(); const t0 = Date.now();
  await sleep(ms);
  const b = await probe(); const dt = Date.now() - t0;
  const d = { hostFrames: b.hostFrames - a.hostFrames, hostContent: b.hostContent - a.hostContent, presenterFrames: b.presenterFrames - a.presenterFrames, ms: dt };
  d.fps = +(d.hostFrames / (dt / 1000)).toFixed(1);
  say(`${name}: hostFrames=${d.hostFrames} (${d.fps}/s) hostContent=${d.hostContent} presenterFrames=${d.presenterFrames} over ${dt}ms`);
  results[name] = d;
  return d;
};

const clickChrome = (needle) => ev(`(() => { const el=[...document.querySelectorAll("button")].find(e=>(e.innerText||"").trim().startsWith(${JSON.stringify(needle)}));
  if (!el) return false; el.click(); return true; })()`);
const tap = async (x, y) => {
  for (const type of ["mousePressed", "mouseReleased"])
    await cmd("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1, buttons: type === "mousePressed" ? 1 : 0 });
};
const waitVal = async (key, want, ms = 15000) => {
  const t0 = Date.now();
  for (;;) { const v = await val(key); if (v === want) return Date.now() - t0;
    if (Date.now() - t0 > ms) { say(`TIMEOUT waiting ${key}=${want}, saw ${JSON.stringify(v)}`); return -1; } await sleep(150); }
};

await quiet("idleBeforeLive", 3000);
await clickChrome("▶ Live");
await sleep(1500);
say(`live: ${await panels()}`);
await quiet("idleLiveWaitingOnGate", 3000);

// asynchronous completion, no interaction
const a0 = await probe();
await ev(`localStorage.setItem("ledgerStart","1"); true`);
const took = await waitVal("status", "loaded 42 entries");
const a1 = await probe();
results.asyncCompletion = { ms: took, hostFrames: a1.hostFrames - a0.hostFrames, hostContent: a1.hostContent - a0.hostContent, presenterFrames: a1.presenterFrames - a0.presenterFrames };
say(`async completion: ${took}ms, hostFrames+${results.asyncCompletion.hostFrames} hostContent+${results.asyncCompletion.hostContent} presenterFrames+${results.asyncCompletion.presenterFrames}`);

// three synchronous canvas taps
results.taps = [];
for (let i = 1; i <= 3; i++) {
  const b0 = await probe();
  await tap(640, 182);
  const ms = await waitVal("tally", `${i} entries`, 8000);
  const b1 = await probe();
  results.taps.push({ i, ms, hostContent: b1.hostContent - b0.hostContent, presenterFrames: b1.presenterFrames - b0.presenterFrames });
  say(`tap ${i}: ${ms}ms hostContent+${b1.hostContent - b0.hostContent} presenterFrames+${b1.presenterFrames - b0.presenterFrames}`);
}

await clickChrome("■ Stop");
await sleep(1500);
say(`after Stop: ${await panels()}`);
await quiet("idleAfterStop", 3000);

fs.writeFileSync(`${out}/${label}-measure.json`, JSON.stringify(results, null, 2));
fs.writeFileSync(`${out}/${label}-measure.log`, lines.join("\n"));
socket.close();
