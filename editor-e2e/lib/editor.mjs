// What a test needs to know about the editor and its relay: where things are
// on the page (read from the DOM), what the relay's document says, and where
// the canvas drew a given colour. Input still goes through Page's real-input
// methods; nothing here clicks or edits by script.
import { argbToRgb, findColor } from './png.mjs';
import { sleep } from './browser.mjs';

export class Relay {
  constructor(port) { this.port = port; this.base = `http://127.0.0.1:${port}`; }

  async get(path) {
    const r = await fetch(this.base + path);
    if (!r.ok) throw new Error(`relay GET ${path}: ${r.status}`);
    return r.text();
  }

  /** Makes [screen] the relay's active screen: the one the editor opens. Scratch relays only. */
  async setActive(screen, project = 'default') {
    const r = await fetch(`${this.base}/active?project=${project}&screen=${screen}`, { method: 'POST' });
    if (!r.ok) throw new Error(`relay POST /active: ${r.status}`);
  }

  /** The screen's document as a plain tree: { handle, type, props: {name: literal|{bind}}, children }. */
  async doc(screen, project = 'default') {
    const d = JSON.parse(await this.get(`/doc?project=${project}&screen=${screen}`));
    const lit = (v) => {
      if (!v || typeof v !== 'object') return v;
      if ('s' in v) return v.s; if ('i' in v) return v.i; if ('d' in v) return v.d; if ('b' in v) return v.b;
      if (v.field) return { bind: v.field };
      return v;
    };
    const conv = (n) => ({
      handle: n.handle, type: n.type,
      props: Object.fromEntries(Object.entries(n.props ?? {}).map(([k, v]) => [k, lit(v)])),
      children: (n.children ?? []).map(conv),
    });
    return conv(d.root);
  }

  /** The project's component specs (with their definition bodies, whose ids are definition-local). */
  async components(project = 'default') {
    return JSON.parse(await this.get(`/components?project=${project}`));
  }
}

/** Every node of [tree] (a relay doc or a component body), depth first. */
export function nodes(tree) {
  const out = [];
  const walk = (n) => { out.push(n); (n.children ?? []).forEach(walk); };
  walk(tree);
  return out;
}

export class Editor {
  constructor(page, { url, relay }) { this.page = page; this.url = url; this.relay = relay; }

  /** Opens the editor on [screen] and waits until the outline lists it and the canvas has drawn [readyColor]. */
  async open(screen, { readyColor } = {}) {
    await this.relay.setActive(screen);
    const t0 = Date.now();
    await this.page.goto(`${this.url}/?relay=${this.relay.port}`);
    await this.page.waitFor(`document.querySelectorAll('.tree-row').length > 0`,
      { timeout: 60000, message: 'the outline never listed any node (editor did not load, or cannot reach the relay)' });
    this.outlineReadyMs = Date.now() - t0;
    await this.page.waitFor(`document.querySelector('#PreviewHost canvas') != null`,
      { timeout: 30000, message: 'the canvas was never created' });
    if (readyColor != null) {
      await this.waitForColor(readyColor, { message: 'the canvas never drew the screen' });
    } else {
      await sleep(1000);
    }
    this.canvasReadyMs = Date.now() - t0;
  }

  /** The outline's rows: { text, type, selected, depth, rect }. */
  async outline() {
    return this.page.eval(`[...document.querySelectorAll('.tree-row')].map(r => {
      const b = r.getBoundingClientRect();
      return { text: r.textContent, type: r.querySelector('.t')?.textContent ?? '', selected: r.classList.contains('selected'),
        indent: parseFloat(r.style.paddingLeft) || 0, rect: { x: b.x, y: b.y, width: b.width, height: b.height } };
    })`);
  }

  /** The selected outline rows' texts. */
  async selectedRows() {
    return this.page.eval(`[...document.querySelectorAll('.tree-row.selected')].map(r => r.textContent)`);
  }

  /** The properties panel's header line, e.g. "SummaryCard #5 · component", or its empty-state text. */
  async propsHeader() {
    return this.page.eval(`document.querySelector('.pane.right .card')?.firstElementChild?.textContent ?? null`);
  }

  /** The selected node's handle, read from the properties header ("<Type> #<id>…"), or null. */
  async selectedHandle() {
    const h = await this.propsHeader();
    const m = /#(\d+)/.exec(h ?? '');
    return m ? +m[1] : null;
  }

  /**
   * Real-clicks the outline row at [index] (wheeling the pane to it), on its type label: a row
   * can start with a disclosure triangle, and a click there collapses instead of selecting.
   */
  async clickOutlineRow(index, { settle = 60 } = {}) {
    const where = () => this.page.eval(`(() => {
      const r = document.querySelectorAll('.tree-row')[${index}];
      if (!r) return null;
      const b = (r.querySelector('.t') ?? r).getBoundingClientRect();
      const pane = r.closest('.pane').getBoundingClientRect();
      return { x: b.x + Math.min(b.width / 2, 30), y: b.y + b.height / 2,
        above: b.top < pane.top, below: b.bottom > Math.min(pane.bottom, innerHeight) };
    })()`);
    let p = await where();
    if (!p) throw new Error(`no outline row ${index}`);
    // Out of view: wheel the outline's pane (a real wheel) until it is in view.
    for (let i = 0; i < 30 && (p.above || p.below); i++) {
      await this.page.wheel(120, 600, p.below ? 200 : -200, { settle: 60 });
      p = await where();
    }
    if (p.above || p.below) throw new Error(`outline row ${index} never scrolled into view`);
    // The outline re-renders synchronously on a click; [settle] covers the canvas's overlay tick.
    await this.page.click(p.x, p.y, { settle });
  }

  /** Real-clicks the first outline row whose type is [type] (and whose text includes [text]). */
  async clickOutlineRowOf(type, text = '') {
    const rows = await this.outline();
    const i = rows.findIndex((r) => r.type === type && r.text.includes(text));
    if (i < 0) throw new Error(`no outline row for ${type} ${text}`.trim());
    await this.clickOutlineRow(i);
  }

  /**
   * Clears the selection with real input: Escape (which also deselects on a click
   * into empty canvas). An editor without that (before E34) keeps its selection;
   * then the outline's root row is clicked, so "what a click selects" starts from
   * a known state either way. Returns the handle selected afterwards (null = none).
   */
  async clearSelection(rootHandle) {
    await this.page.press('Escape', { settle: 80 });
    if ((await this.selectedHandle()) != null) {
      await this.clickOutlineRow(0);
      const h = await this.selectedHandle();
      if (h !== rootHandle) throw new Error(`could not reset the selection (it is ${h})`);
      return h;
    }
    return null;
  }

  /**
   * The centre (page px) of the first element matching [selector] whose text includes [text]
   * (or is exactly [text], with [exact]); null if none.
   */
  async centreOf(selector, text = '', { exact = false } = {}) {
    return this.page.eval(`(() => {
      const want = ${JSON.stringify(text)};
      const e = [...document.querySelectorAll(${JSON.stringify(selector)})]
        .find(e => (${exact} ? e.textContent.trim() === want : e.textContent.includes(want)) && e.getBoundingClientRect().width > 0);
      if (!e) return null;
      const b = e.getBoundingClientRect();
      return { x: b.x + b.width / 2, y: b.y + b.height / 2 };
    })()`);
  }

  /** Real-clicks the first element matching [selector] whose text includes [text]. */
  async clickOn(selector, text = '', opts = {}) {
    const c = await this.centreOf(selector, text, opts);
    if (!c) throw new Error(`nothing to click: ${selector} ${JSON.stringify(text)}`);
    await this.page.click(c.x, c.y, opts);
  }

  /** The toolbar's Undo and Redo buttons: { undo: {disabled, title}, redo: {…} }. */
  async undoRedo() {
    return this.page.eval(`(() => {
      const f = (w) => { const b = [...document.querySelectorAll('.topbar button')].find(b => b.textContent.includes(w));
        return b ? { disabled: b.disabled || b.hasAttribute('disabled'), title: b.title } : null; };
      return { undo: f('Undo'), redo: f('Redo') };
    })()`);
  }

  async clickUndo() { await this.clickOn('.topbar button', 'Undo'); }
  async clickRedo() { await this.clickOn('.topbar button', 'Redo'); }

  /** The save pill's text ("Saved", "Change refused", …). */
  async pill() {
    return this.page.eval(`document.querySelector('.topbar .pill')?.textContent ?? null`);
  }

  /** The breadcrumb's text while a component is open (null when hidden, i.e. editing the screen). */
  async crumbs() {
    return this.page.eval(`(() => { const c = document.querySelector('.crumbs');
      return c && getComputedStyle(c).display !== 'none' ? c.textContent : null; })()`);
  }

  /** The text of the canvas hint, while it shows (null otherwise). */
  async canvasHint() {
    return this.page.eval(`(() => { const h = document.querySelector('.canvas-hint');
      return h && getComputedStyle(h).display !== 'none' ? h.textContent : null; })()`);
  }

  /** The properties field labelled [label] (its value and centre), or null. */
  async propField(label) {
    return this.page.eval(`(() => {
      const row = [...document.querySelectorAll('.pane.right .row')].find(r => r.querySelector('label')?.textContent === ${JSON.stringify(label)});
      const f = row?.querySelector('textarea, input');
      if (!f) return null;
      const b = f.getBoundingClientRect();
      return { value: f.value, x: b.x + Math.min(b.width / 2, 40), y: b.y + b.height / 2, tag: f.tagName };
    })()`);
  }

  /** Real-clicks into the field labelled [label], moves the caret to its end and types [text]. */
  async appendToField(label, text) {
    const f = await this.propField(label);
    if (!f) throw new Error(`no properties field labelled ${label}`);
    await this.page.click(f.x, f.y);
    await this.page.caretToEnd();
    await this.page.type(text);
    return f.value;
  }

  /** The phone frame's rect (page px). */
  async frameRect() {
    return this.page.eval(`(() => { const b = document.getElementById('PreviewHost').getBoundingClientRect();
      return { x: b.x, y: b.y, width: b.width, height: b.height }; })()`);
  }

  /** The rect the selection overlay outlines (page px), or null when hidden. */
  async selectionOverlay() {
    return this.page.eval(`(() => {
      const host = document.getElementById('PreviewHost');
      // The selection box is the frame's solid-bordered, click-through child (the hover box is dashed).
      const o = [...host.parentElement.children].find(e => e !== host && e.style.pointerEvents === 'none' && e.style.borderTopStyle === 'solid');
      if (!o || o.style.display === 'none') return null;
      // Its content box is the selected node's rect; the border is drawn outside it.
      const b = o.getBoundingClientRect();
      return { x: b.x + o.clientLeft, y: b.y + o.clientTop, width: o.clientWidth, height: o.clientHeight };
    })()`);
  }

  /** Where the canvas drew [argb] (a Kotlin ARGB int) inside the frame, in page px; null if nowhere. */
  async findColor(argb, { name, minPixels = 40 } = {}) {
    const frame = await this.frameRect();
    const img = await this.page.capture(frame, name);
    return findColor(img, argbToRgb(argb), { origin: img.origin, minPixels });
  }

  async waitForColor(argb, { timeout = 20000, message } = {}) {
    const end = Date.now() + timeout;
    for (;;) {
      const hit = await this.findColor(argb);
      if (hit) return hit;
      if (Date.now() > end) throw new Error(message ?? `the canvas never drew colour ${argb}`);
      await sleep(250);
    }
  }

  /** Widths of the left and right panes (0 when collapsed / hidden). */
  async paneWidths() {
    return this.page.eval(`(() => {
      const w = (sel) => { const e = document.querySelector(sel); if (!e) return null;
        return getComputedStyle(e).display === 'none' ? 0 : e.getBoundingClientRect().width; };
      return { left: w('.pane.left'), right: w('.pane.right') };
    })()`);
  }
}
