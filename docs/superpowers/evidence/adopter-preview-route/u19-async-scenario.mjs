// U19 asynchronous verification, driven over CDP against a real editor build.
// Usage: node scenario.mjs <devtools-port> <out-dir> <label> [url]
import fs from "node:fs";

const port = process.argv[2];
const out = process.argv[3];
const label = process.argv[4];
const url = process.argv[5] ?? "http://localhost:8096/";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const lines = [];
const say = (m) => { console.log(m); lines.push(m); };

const targets = await fetch(`http://127.0.0.1:${port}/json`).then((r) => r.json());
const page = targets.find((t) => t.type === "page");
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => {
  socket.addEventListener("open", res, { once: true });
  socket.addEventListener("error", rej, { once: true });
});
let nextId = 0;
const pending = new Map();
socket.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (!m.id || !pending.has(m.id)) return;
  const { resolve, reject } = pending.get(m.id);
  pending.delete(m.id);
  if (m.error) reject(new Error(JSON.stringify(m.error)));
  else resolve(m.result);
});
const cmd = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId;
  pending.set(id, { resolve, reject });
  socket.send(JSON.stringify({ id, method, params }));
});
const ev = async (expression) => {
  const r = await cmd("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 500));
  return r.result.value;
};

await cmd("Page.enable");
await cmd("Runtime.enable");
await cmd("Emulation.setDeviceMetricsOverride", { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
// Clear any release flags from a previous run BEFORE the app starts.
await cmd("Page.navigate", { url });
await sleep(800);
await ev(`localStorage.clear(); true`);
await cmd("Page.navigate", { url });
await sleep(1200);
await ev(`new Promise((resolve, reject) => {
  const deadline = Date.now() + 60000;
  const poll = () => {
    const c = [...document.querySelectorAll("canvas")].find(x => x.width > 0);
    if (c) resolve(true);
    else if (Date.now() > deadline) reject(new Error("canvas did not mount"));
    else setTimeout(poll, 100);
  };
  poll();
})`);
say(`[${label}] canvas mounted`);

const shot = async (name) => {
  const r = await cmd("Page.captureScreenshot", { format: "png" });
  fs.writeFileSync(`${out}/${label}-${name}.png`, Buffer.from(r.data, "base64"));
};

// --- the DOM panels ---------------------------------------------------------
const panels = () => ev(`(() => {
  const t = document.body.innerText || "";
  const grab = (name, next) => {
    const i = t.indexOf(name); if (i < 0) return "";
    const j = next ? t.indexOf(next, i) : -1;
    return t.slice(i + name.length, j < 0 ? i + name.length + 400 : j).trim();
  };
  return JSON.stringify({
    inspector: grab("STATE INSPECTOR", "ACTION CONSOLE"),
    console: grab("ACTION CONSOLE", null).slice(0, 300),
    fidelity: grab("PREVIEW FIDELITY", "Runtime compatibility"),
  });
})()`).then(JSON.parse);

const value = (inspector, key) => {
  const m = inspector.match(new RegExp(`${key}\\s*=\\s*"([^"]*)"`));
  return m ? m[1] : null;
};

const state = async () => {
  const p = await panels();
  return { status: value(p.inspector, "status"), tally: value(p.inspector, "tally"), raw: p.inspector };
};

// Wait for a predicate WITHOUT interacting with the page at all.
const settle = async (name, pred, ms = 15000) => {
  const t0 = Date.now();
  for (;;) {
    const s = await state();
    if (pred(s)) { say(`[${label}] ${name}: status=${JSON.stringify(s.status)} tally=${JSON.stringify(s.tally)} (+${Date.now() - t0}ms)`); return s; }
    if (Date.now() - t0 > ms) { say(`[${label}] ${name}: TIMEOUT after ${ms}ms — status=${JSON.stringify(s.status)} tally=${JSON.stringify(s.tally)}`); return s; }
    await sleep(200);
  }
};

// Assert a value stays put for a while — this is how we show the host is idle.
const hold = async (name, ms) => {
  const seen = new Set();
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { const s = await state(); seen.add(`${s.status}|${s.tally}`); await sleep(250); }
  say(`[${label}] ${name}: held ${ms}ms, distinct states = ${JSON.stringify([...seen])}`);
  return [...seen];
};

const clickChrome = (needle) => ev(`(() => {
  const el = [...document.querySelectorAll("button")].find(e => (e.innerText||"").trim().startsWith(${JSON.stringify(needle)}));
  if (!el) return false; el.click(); return true;
})()`);

// A real mouse event on the Compose canvas, at the centre of a rendered label.
const canvasBox = () => ev(`JSON.stringify((() => {
  const c = [...document.querySelectorAll("canvas")].find(x => x.width > 0);
  const r = c.getBoundingClientRect();
  return {x: r.x, y: r.y, w: r.width, h: r.height};
})())`).then(JSON.parse);

const tap = async (x, y) => {
  for (const type of ["mousePressed", "mouseReleased"]) {
    await cmd("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1, buttons: type === "mousePressed" ? 1 : 0 });
  }
};

// --- scenario ---------------------------------------------------------------
const results = {};
await shot("01-mock");
say(`[${label}] before Live: ${JSON.stringify(await panels().then(p => p.fidelity))}`);

await clickChrome("▶ Live");
const live = await settle("live started", (s) => s.status !== null, 20000);
results.liveStart = live;
await shot("02-live");

// 1. asynchronous completion, with no interaction of any kind.
results.beforeRelease = await hold("before release", 4000);
await ev(`localStorage.setItem("ledgerStart", "1"); true`);   // the gate, opened from outside the app
const released = await settle("async completion (no interaction)", (s) => s.status === "loaded 42 entries", 15000);
results.asyncCompletion = released;
await shot("03-async-completed");

// 2. three synchronous actions, tapped on the CANVAS (real mouse events).
const ADD = { x: 640, y: 182 };
const REFRESH = { x: 639, y: 230 };
results.taps = [];
for (let i = 1; i <= 3; i++) {
  await tap(ADD.x, ADD.y);
  const s = await settle(`canvas tap ${i}`, (x) => x.tally === `${i} entries`, 8000);
  results.taps.push(s.tally);
}
await shot("04-three-taps");

// 3. an action that STARTS async work which completes after that render settled.
await tap(REFRESH.x, REFRESH.y);
const refreshing = await settle("refresh dispatched", (s) => s.status === "refreshing…", 8000);
results.refreshing = refreshing.status;
results.duringRefresh = await hold("host idle while the request is outstanding", 4000);
await ev(`localStorage.setItem("ledgerRefresh1", "1"); true`);
const refreshed = await settle("delayed completion (no interaction)", (s) => s.status === "refreshed 1", 15000);
results.refreshed = refreshed;
await shot("05-refresh-completed");

// 4. stop and restart: no stale value from the previous session.
await clickChrome("■ Stop");
await sleep(1500);
results.afterStop = await state().then((s) => ({ status: s.status, tally: s.tally }));
say(`[${label}] after Stop: ${JSON.stringify(results.afterStop)}`);
await ev(`localStorage.removeItem("ledgerStart"); localStorage.removeItem("ledgerRefresh1"); true`);
await clickChrome("▶ Live");
const restarted = await settle("restarted", (s) => s.status !== null, 15000);
results.restarted = { status: restarted.status, tally: restarted.tally };
results.afterRestartHold = await hold("restarted session holds its own state", 3000);
await shot("06-restarted");

const box = await canvasBox();
say(`[${label}] canvas box ${JSON.stringify(box)}`);
results.canvasBox = box;

fs.writeFileSync(`${out}/${label}-log.txt`, lines.join("\n"));
fs.writeFileSync(`${out}/${label}-results.json`, JSON.stringify(results, null, 2));
socket.close();
