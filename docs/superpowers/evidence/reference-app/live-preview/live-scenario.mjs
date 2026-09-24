import fs from "node:fs"; import { connect, sleep } from "./cdp.mjs";
const [port, out] = process.argv.slice(2);
const { cmd, ev, socket } = await connect(port);
const log = []; const say = (m) => { console.log(m); log.push(m); };
await cmd("Page.enable"); await cmd("Runtime.enable");
await cmd("Emulation.setDeviceMetricsOverride", { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
const panels = () => ev(`(() => { const t = document.body.innerText || "";
  const grab = (a, b) => { const i = t.indexOf(a); if (i < 0) return ""; const j = b ? t.indexOf(b, i) : -1; return t.slice(i + a.length, j < 0 ? i + a.length + 600 : j).trim(); };
  return JSON.stringify({ fidelity: grab("PREVIEW FIDELITY", "Runtime compatibility"), inspector: grab("STATE INSPECTOR", "ACTION CONSOLE"), console: grab("ACTION CONSOLE", null).slice(0, 600) }); })()`).then(JSON.parse);
const val = (p, k) => { const m = p.inspector.match(new RegExp(`${k} = "([^"]*)"`)); return m ? m[1] : null; };
const shot = async (n) => fs.writeFileSync(`${out}/${n}.png`, Buffer.from((await cmd("Page.captureScreenshot", { format: "png" })).data, "base64"));
const tap = async (x, y) => { for (const type of ["mousePressed", "mouseReleased"]) await cmd("Input.dispatchMouseEvent", { type, x, y, button: "left", clickCount: 1, buttons: type === "mousePressed" ? 1 : 0 }); };
const select = (screen) => ev(`(() => { const s = [...document.querySelectorAll("select")].find(x => [...x.options].some(o => o.value === "${screen}"));
  s.value = "${screen}"; s.dispatchEvent(new Event("change", { bubbles: true })); return true; })()`);
const clickBtn = (needle) => ev(`(() => { const b = [...document.querySelectorAll("button")].find(e => (e.innerText||"").trim().startsWith(${JSON.stringify(needle)})); if (!b) return false; b.click(); return true; })()`);
const settle = async (name, pred, ms = 8000) => { const t0 = Date.now(); for (;;) { const p = await panels(); if (pred(p)) { return p; } if (Date.now() - t0 > ms) { say(`  TIMEOUT ${name}`); return p; } await sleep(200); } };
const results = [];
const check = (id, what, ok, observed) => { results.push({ id, what, ok, observed }); say(`  ${ok ? "PASS" : "FAIL"}  ${id}  ${what}${ok ? "" : "  observed: " + JSON.stringify(observed)}`); };

await cmd("Page.navigate", { url: "http://localhost:8096/" }); await sleep(8000);
// --- item screen ---
await select("item"); await sleep(2500);
let p = await panels();
check("L0", "mock mode before Live", p.fidelity.startsWith("Mock mode"), p.fidelity);
await clickBtn("▶ Live");
p = await settle("live", (p) => val(p, "quantityLabel") !== null);
check("L1", "Live runs the real presenter (fidelity panel)", /real presenter — inventory \(real presenters\)/.test(p.fidelity), p.fidelity);
check("L2", "real seed value: quantityLabel = On hand: 12", val(p, "quantityLabel") === "On hand: 12", val(p, "quantityLabel"));
await shot("L-item-live-12");
const ADD = [743, 225], REMOVE = [645, 225], RECEIVE = [648, 273];
for (const n of [13, 14, 15]) {
  await tap(...ADD);
  p = await settle(`add ${n}`, (p) => val(p, "quantityLabel") === `On hand: ${n}`);
  check(`L3.${n}`, `canvas tap Add 1 -> On hand: ${n}`, val(p, "quantityLabel") === `On hand: ${n}`, val(p, "quantityLabel"));
}
check("L3.h", "history accumulates: 3 adjustments: +1, +1, +1 (net +3)", val(p, "historyLabel") === "3 adjustments: +1, +1, +1 (net +3)", val(p, "historyLabel"));
await shot("L-item-live-15");
for (let n = 14; n >= 5; n--) {
  await tap(...REMOVE);
  p = await settle(`remove ${n}`, (p) => val(p, "quantityLabel") === `On hand: ${n}`);
  const low = val(p, "isLowStock");
  check(`L4.${n}`, `canvas tap Remove 1 -> On hand: ${n}, isLowStock ${n <= 5}`, val(p, "quantityLabel") === `On hand: ${n}` && low === String(n <= 5), [val(p, "quantityLabel"), low]);
}
await shot("L-item-live-5-low");
await tap(...RECEIVE);
p = await settle("receive", (p) => val(p, "quantityLabel") !== "On hand: 5");
check("L5", "canvas tap Receive 10 -> On hand: 15", val(p, "quantityLabel") === "On hand: 15", [val(p, "quantityLabel"), p.console.slice(0, 200)]);
say("  action console: " + p.console.replace(/\n/g, " | ").slice(0, 400));
await clickBtn("■ Stop"); await sleep(1500);
p = await panels();
check("L6", "Stop clears the live values", val(p, "quantityLabel") === null, p.inspector.slice(0, 120));
await clickBtn("▶ Live");
p = await settle("restart", (p) => val(p, "quantityLabel") !== null);
check("L7", "a restarted Live session starts from the seed again (On hand: 12)", val(p, "quantityLabel") === "On hand: 12", val(p, "quantityLabel"));
await clickBtn("■ Stop"); await sleep(1000);
// --- list screen ---
await select("inventory"); await sleep(2500);
await clickBtn("▶ Live");
p = await settle("list live", (p) => val(p, "summary") !== null);
check("L8", "list screen live: summary = 8 items · 2 low on stock", val(p, "summary") === "8 items · 2 low on stock", val(p, "summary"));
await shot("L-list-live");
fs.writeFileSync(`${out}/preview-log.txt`, log.join("\n")); fs.writeFileSync(`${out}/preview-results.json`, JSON.stringify(results, null, 1));
say(`preview: passed ${results.filter(r => r.ok).length}, failed ${results.filter(r => !r.ok).length}`);
socket.close();
