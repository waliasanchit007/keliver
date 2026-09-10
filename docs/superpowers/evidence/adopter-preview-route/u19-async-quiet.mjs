// Quietest possible observation: click Live, open the gate, then touch nothing
// for 6 seconds, then read the State Inspector exactly once.
const port = process.argv[2];
const label = process.argv[3];
const url = process.argv[4] ?? "http://localhost:8096/";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const targets = await fetch(`http://127.0.0.1:${port}/json`).then((r) => r.json());
const page = targets.find((t) => t.type === "page");
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => {
  socket.addEventListener("open", res, { once: true });
  socket.addEventListener("error", rej, { once: true });
});
let nextId = 0; const pending = new Map();
socket.addEventListener("message", (e) => {
  const m = JSON.parse(e.data);
  if (!m.id || !pending.has(m.id)) return;
  const { resolve, reject } = pending.get(m.id); pending.delete(m.id);
  if (m.error) reject(new Error(JSON.stringify(m.error))); else resolve(m.result);
});
const cmd = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId; pending.set(id, { resolve, reject });
  socket.send(JSON.stringify({ id, method, params }));
});
const ev = async (expression) => {
  const r = await cmd("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 400));
  return r.result.value;
};
const inspector = () => ev(`(() => { const t=document.body.innerText; const i=t.indexOf("STATE INSPECTOR");
  return t.slice(i, i + 110).replace(/\\n/g, " | "); })()`);

await cmd("Page.enable"); await cmd("Runtime.enable");
await cmd("Emulation.setDeviceMetricsOverride", { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
await cmd("Page.navigate", { url }); await sleep(1200);
await ev(`localStorage.clear(); true`);
await cmd("Page.navigate", { url }); await sleep(1500);
await ev(`new Promise((res, rej) => { const d = Date.now()+60000; const p = () => {
  const c = [...document.querySelectorAll("canvas")].find(x => x.width > 0);
  if (c) res(true); else if (Date.now() > d) rej(new Error("no canvas")); else setTimeout(p, 100); }; p(); })`);

await ev(`(() => { const el=[...document.querySelectorAll("button")].find(e=>(e.innerText||"").trim().startsWith("▶ Live")); el.click(); return true; })()`);
await sleep(3000);
console.log(`[${label}] after Live, before the gate:`, await inspector());

// Open the gate. setItem touches no DOM and forces no layout.
await ev(`localStorage.setItem("ledgerStart","1"); true`);
await sleep(6000);   // six seconds of complete quiet: no evaluate, no screenshot
console.log(`[${label}] 6s after the gate, first read:`, await inspector());
socket.close();
