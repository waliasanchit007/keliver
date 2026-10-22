// Headless Chrome over the DevTools protocol, with REAL input only: every
// click, drag, wheel and keystroke goes through Input.dispatchMouseEvent /
// Input.dispatchKeyEvent, exactly as a user's would. Page script is used to
// READ state (DOM, geometry), never to drive the editor.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { decodePng } from './png.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export { sleep };

/** The Chrome/Chromium binary: $CHROME, else the usual macOS and Linux locations, else PATH. */
export function findChrome() {
  if (process.env.CHROME) return process.env.CHROME;
  const candidates = process.platform === 'darwin'
    ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/Applications/Chromium.app/Contents/MacOS/Chromium',
      path.join(os.homedir(), 'Applications/Google Chrome.app/Contents/MacOS/Google Chrome')]
    : ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/opt/google/chrome/chrome',
      '/usr/bin/chromium', '/usr/bin/chromium-browser', '/snap/bin/chromium'];
  for (const c of candidates) if (fs.existsSync(c)) return c;
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    for (const name of ['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser']) {
      const p = path.join(dir, name);
      if (fs.existsSync(p)) return p;
    }
  }
  throw new Error('Chrome not found: install Google Chrome or Chromium, or set CHROME=<binary>');
}

/**
 * Starts Chrome on a fresh, empty profile ([profileDir]) and returns a
 * connected [Page]. The editor's loader has a constant file name, so a reused
 * profile can run a stale cached build: every test gets its own profile.
 */
export async function launch({ profileDir, width = 1440, height = 900, artifacts }) {
  fs.mkdirSync(profileDir, { recursive: true });
  const args = [
    '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profileDir}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--mute-audio',
    '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
    '--disable-backgrounding-occluded-windows', '--force-device-scale-factor=1',
    `--window-size=${width},${height}`,
  ];
  if (process.platform === 'linux') {
    // Cloud runners have no GPU: the canvas (WebGL) runs on SwiftShader, which
    // Chrome only enables for WebGL when asked. The sandbox needs user
    // namespaces that CI containers often forbid.
    args.push('--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--disable-dev-shm-usage');
  }
  args.push('about:blank');
  const proc = spawn(findChrome(), args, { stdio: ['ignore', 'ignore', 'pipe'] });
  let stderr = '';
  proc.stderr.on('data', (d) => { stderr = (stderr + d).slice(-4000); });
  const portFile = path.join(profileDir, 'DevToolsActivePort');
  let port = null;
  for (let i = 0; i < 150 && port == null; i++) {
    if (proc.exitCode != null) throw new Error(`Chrome exited (${proc.exitCode}): ${stderr}`);
    try { port = +fs.readFileSync(portFile, 'utf8').split('\n')[0] || null; } catch { /* not yet */ }
    if (port == null) await sleep(100);
  }
  if (port == null) { proc.kill('SIGKILL'); throw new Error(`Chrome did not open a DevTools port: ${stderr}`); }
  let target = null;
  for (let i = 0; i < 50 && !target; i++) {
    try { target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((t) => t.type === 'page'); } catch { /* retry */ }
    if (!target) await sleep(100);
  }
  if (!target) { proc.kill('SIGKILL'); throw new Error('no page target'); }
  const page = new Page(proc, target.webSocketDebuggerUrl, { width, height, artifacts });
  await page.open();
  return page;
}

const KEYS = {
  Enter: 13, Tab: 9, Backspace: 8, Delete: 46, Escape: 27, ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39,
  ArrowDown: 40, Home: 36, End: 35, PageUp: 33, PageDown: 34, ' ': 32,
};
const MODS = { Alt: 1, Control: 2, Meta: 4, Shift: 8 };

export class Page {
  constructor(proc, wsUrl, { width, height, artifacts }) {
    this.proc = proc; this.wsUrl = wsUrl; this.width = width; this.height = height;
    this.artifacts = artifacts; this.nextId = 0; this.pending = new Map(); this.listeners = [];
    /** Everything the page said: console calls, exceptions, log entries, failed requests. */
    this.console = [];
    this.exceptions = [];
    this.mouse = { x: 0, y: 0 };
  }

  async open() {
    this.ws = new WebSocket(this.wsUrl);
    await new Promise((resolve, reject) => { this.ws.onopen = resolve; this.ws.onerror = reject; });
    this.ws.onmessage = (m) => {
      const d = JSON.parse(m.data);
      if (d.id && this.pending.has(d.id)) { this.pending.get(d.id)(d); this.pending.delete(d.id); return; }
      if (d.method === 'Runtime.consoleAPICalled') {
        this.console.push({ type: d.params.type, text: d.params.args.map((a) => a.value ?? a.description).join(' ') });
      } else if (d.method === 'Runtime.exceptionThrown') {
        const e = d.params.exceptionDetails;
        this.exceptions.push(e.exception?.description ?? e.text);
      } else if (d.method === 'Log.entryAdded') {
        this.console.push({ type: `log.${d.params.entry.level}`, text: `${d.params.entry.text} ${d.params.entry.url ?? ''}`.trim() });
      }
      for (const l of this.listeners) l(d);
    };
    await this.send('Runtime.enable');
    await this.send('Page.enable');
    await this.send('Log.enable');
    await this.send('Emulation.setDeviceMetricsOverride', { width: this.width, height: this.height, deviceScaleFactor: 1, mobile: false });
  }

  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.nextId;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP ${method} timed out`)); }, 30000);
      this.pending.set(id, (d) => {
        clearTimeout(timer);
        if (d.error) reject(new Error(`CDP ${method}: ${d.error.message}`)); else resolve(d.result);
      });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  async goto(url) {
    const loaded = new Promise((r) => {
      const l = (d) => { if (d.method === 'Page.loadEventFired') { this.listeners.splice(this.listeners.indexOf(l), 1); r(); } };
      this.listeners.push(l);
    });
    await this.send('Page.navigate', { url });
    await Promise.race([loaded, sleep(30000)]);
  }

  async reload() {
    const loaded = new Promise((r) => {
      const l = (d) => { if (d.method === 'Page.loadEventFired') { this.listeners.splice(this.listeners.indexOf(l), 1); r(); } };
      this.listeners.push(l);
    });
    await this.send('Page.reload', { ignoreCache: false });
    await Promise.race([loaded, sleep(30000)]);
  }

  /** Evaluates [expression] in the page (READ-ONLY use: state and geometry) and returns its value. */
  async eval(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(`page eval failed: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}\n  in: ${expression}`);
    return r.result.value;
  }

  /** Polls [expression] until it is truthy; returns its value, or throws [message] after [timeout] ms. */
  async waitFor(expression, { timeout = 15000, interval = 100, message } = {}) {
    const end = Date.now() + timeout;
    let last;
    for (;;) {
      try { last = await this.eval(expression); } catch (e) { last = undefined; }
      if (last) return last;
      if (Date.now() > end) throw new Error(message ?? `timed out after ${timeout} ms waiting for: ${expression}`);
      await sleep(interval);
    }
  }

  // ── Real input ───────────────────────────────────────────────────────────

  async move(x, y, buttons = 0) {
    await this.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, buttons, button: buttons ? 'left' : 'none', pointerType: 'mouse' });
    this.mouse = { x, y };
  }

  async down(x, y, clickCount = 1) {
    await this.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', buttons: 1, clickCount, pointerType: 'mouse' });
  }

  async up(x, y, clickCount = 1) {
    await this.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', buttons: 0, clickCount, pointerType: 'mouse' });
  }

  /** A real left click at page coordinates ([x], [y]): move there, press, release. */
  async click(x, y, { settle = 250 } = {}) {
    await this.move(x, y);
    await this.down(x, y);
    await sleep(40);
    await this.up(x, y);
    if (settle) await sleep(settle);
  }

  async dblclick(x, y, { settle = 300 } = {}) {
    await this.move(x, y);
    for (const c of [1, 2]) { await this.down(x, y, c); await sleep(30); await this.up(x, y, c); await sleep(30); }
    if (settle) await sleep(settle);
  }

  /** Press at ([x1],[y1]), move in [steps] steps to ([x2],[y2]), release. */
  async drag(x1, y1, x2, y2, { steps = 12, settle = 300 } = {}) {
    await this.move(x1, y1);
    await this.down(x1, y1);
    for (let k = 1; k <= steps; k++) {
      await this.move(x1 + ((x2 - x1) * k) / steps, y1 + ((y2 - y1) * k) / steps, 1);
      await sleep(16);
    }
    await this.up(x2, y2);
    if (settle) await sleep(settle);
  }

  /**
   * An HTML5 drag and drop, as a user's mouse does it: press on the draggable at
   * ([x1],[y1]) and move; Chrome starts the drag (intercepted, so its data can be
   * replayed), then dragenter/dragover at ([x2],[y2]), [during] (e.g. to look at the
   * drop marker), and drop. Returns false if the press started no drag.
   */
  async dragAndDrop(x1, y1, x2, y2, { during, settle = 300 } = {}) {
    let data = null;
    const l = (d) => { if (d.method === 'Input.dragIntercepted') data = d.params.data; };
    this.listeners.push(l);
    await this.send('Input.setInterceptDrags', { enabled: true });
    try {
      await this.move(x1, y1);
      await this.down(x1, y1);
      for (let k = 1; k <= 10 && !data; k++) {
        await this.move(x1 + ((x2 - x1) * k) / 10, y1 + ((y2 - y1) * k) / 10, 1);
        await sleep(30);
      }
      for (let i = 0; i < 20 && !data; i++) await sleep(50);
      if (!data) { await this.up(x2, y2); return false; }
      await this.send('Input.dispatchDragEvent', { type: 'dragEnter', x: x2, y: y2, data });
      for (const dy of [-2, 0]) {
        await this.send('Input.dispatchDragEvent', { type: 'dragOver', x: x2, y: y2 + dy, data });
        await sleep(60);
      }
      if (during) await during();
      await this.send('Input.dispatchDragEvent', { type: 'drop', x: x2, y: y2, data });
      await this.up(x2, y2);
      return true;
    } finally {
      this.listeners.splice(this.listeners.indexOf(l), 1);
      await this.send('Input.setInterceptDrags', { enabled: false });
      if (settle) await sleep(settle);
    }
  }

  /** Mouse-wheel at ([x],[y]) by [dy] px, in [steps] notches (as a real wheel or trackpad sends). */
  async wheel(x, y, dy, { steps = 1, settle = 400 } = {}) {
    await this.move(x, y);
    for (let k = 0; k < steps; k++) {
      await this.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x, y, deltaX: 0, deltaY: dy / steps, pointerType: 'mouse' });
      await sleep(30);
    }
    if (settle) await sleep(settle);
  }

  /**
   * Presses one key ([key] as in KeyboardEvent.key), with [modifiers] like ['Meta'] or ['Shift'].
   * [commands]: the editing commands macOS binds to the combination (Chrome on macOS takes
   * those from the OS key bindings, which synthetic key events skip), e.g. ['moveToEndOfDocument'].
   */
  async press(key, { modifiers = [], commands, settle = 100 } = {}) {
    const mods = modifiers.reduce((m, k) => m | MODS[k], 0);
    const printable = key.length === 1;
    // A printable character's virtual key code is its key's: letters and digits are their
    // upper-case ASCII; anything else gets 0 (e.g. '!' as 33 would read as PageUp).
    const code = KEYS[key] ?? (/^[a-z0-9]$/i.test(key) ? key.toUpperCase().charCodeAt(0) : 0);
    const base = { key, code: printable ? (/[a-z]/i.test(key) ? `Key${key.toUpperCase()}` : '') : key,
      windowsVirtualKeyCode: code, modifiers: mods };
    // No nativeVirtualKeyCode: native codes are per platform (macOS's Escape is 53, not 27),
    // and a wrong one leaves Chrome on macOS waiting ~5 s before it takes the next mouse event.
    const text = printable && !(mods & (MODS.Control | MODS.Meta)) ? key : undefined;
    await this.send('Input.dispatchKeyEvent', { type: text ? 'keyDown' : 'rawKeyDown', ...base,
      ...(text ? { text, unmodifiedText: text } : {}), ...(commands ? { commands } : {}) });
    await this.send('Input.dispatchKeyEvent', { type: 'keyUp', ...base });
    if (settle) await sleep(settle);
  }

  /** The platform's undo (Cmd+Z on macOS, Ctrl+Z elsewhere); [shift] = redo. */
  async undoKey({ shift = false, settle = 150 } = {}) {
    const mac = process.platform === 'darwin';
    const modifiers = [mac ? 'Meta' : 'Control', ...(shift ? ['Shift'] : [])];
    await this.press('z', { modifiers, commands: mac ? [shift ? 'redo' : 'undo'] : undefined, settle });
  }

  /** Moves the caret to the end of the focused text field: Cmd+Down on macOS, Ctrl+End elsewhere. */
  async caretToEnd() {
    if (process.platform === 'darwin') await this.press('ArrowDown', { modifiers: ['Meta'], commands: ['moveToEndOfDocument'] });
    else await this.press('End', { modifiers: ['Control'] });
  }

  /** Types [text] one key at a time into the focused element. */
  async type(text, { delay = 30 } = {}) {
    for (const ch of text) await this.press(ch, { settle: delay });
  }

  // ── Screenshots ──────────────────────────────────────────────────────────

  /** A PNG of the viewport (or [clip], page px). Saved as artifacts/<name>.png when [name] is given. */
  async screenshot(name, clip) {
    const params = { format: 'png', captureBeyondViewport: false };
    if (clip) params.clip = { x: clip.x, y: clip.y, width: clip.width, height: clip.height, scale: 1 };
    const r = await this.send('Page.captureScreenshot', params);
    const buf = Buffer.from(r.data, 'base64');
    if (name && this.artifacts) {
      fs.mkdirSync(this.artifacts, { recursive: true });
      fs.writeFileSync(path.join(this.artifacts, `${name}.png`), buf);
    }
    return buf;
  }

  /** The decoded screenshot of [clip] (page px), with its origin, for colour search and diffs. */
  async capture(clip, name) {
    const c = clip && { x: Math.round(clip.x), y: Math.round(clip.y), width: Math.round(clip.width), height: Math.round(clip.height) };
    const img = decodePng(await this.screenshot(name, c));
    img.origin = c ? { x: c.x, y: c.y } : { x: 0, y: 0 };
    return img;
  }

  async close() {
    try { await this.send('Browser.close'); } catch { /* already gone */ }
    try { this.ws.close(); } catch { /* ignore */ }
    const exited = new Promise((r) => { if (this.proc.exitCode != null) r(); else this.proc.once('exit', r); });
    await Promise.race([exited, sleep(3000)]);
    if (this.proc.exitCode == null) this.proc.kill('SIGKILL');
  }
}
