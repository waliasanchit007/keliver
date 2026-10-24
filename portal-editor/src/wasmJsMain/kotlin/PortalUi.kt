/*
 * spike/keliver-web portal P2 — the editor's tiny typed UI kit + stylesheet.
 * All-Kotlin DOM (kotlinx-browser), no framework: design tokens as CSS vars,
 * one injected stylesheet, and small helpers the editor chrome builds with.
 */
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement

internal object Ui {
  internal const val STYLE = """
    :root {
      --bg: #16171b; --panel: #1f2026; --panel2: #26272e; --border: #34353e;
      --text: #e6e7ec; --muted: #9a9ba6; --accent: #8b7cf7; --accent2: #6f5ff0;
      --good: #4cc38a; --warn: #f0a05f; --danger: #e5534b; --sel: #2f3040;
      --left-w: 272px; --right-w: 300px;
    }
    html, body { margin: 0; height: 100%; background: var(--bg); color: var(--text);
      font: 13px/1.45 -apple-system, 'Segoe UI', Roboto, sans-serif; overflow: hidden; }
    .topbar { position: fixed; left: 0; top: 0; right: 0; height: 46px; display: flex;
      align-items: center; gap: 10px; padding: 0 14px; background: var(--panel);
      border-bottom: 1px solid var(--border); z-index: 30; box-sizing: border-box; }
    .brand { font-weight: 700; letter-spacing: .3px; color: var(--accent); margin-right: 6px; }
    .crumb { color: var(--muted); }
    .grow { flex: 1; }
    .pane { position: fixed; top: 46px; bottom: 0; overflow: auto; background: var(--panel);
      box-sizing: border-box; z-index: 20; }
    .pane.left { left: 0; width: var(--left-w); border-right: 1px solid var(--border); padding: 10px; }
    .pane.right { right: 0; width: var(--right-w); border-left: 1px solid var(--border); padding: 10px; }
    .pane.collapsed { display: none; }
    .pane.left.stack:not(.collapsed) { display: flex; flex-direction: column; overflow: hidden; }
    .pane.left.stack > .card.outline { flex: 1 1 auto; min-height: 120px; overflow: auto; }
    /* G6: a drag across the editor selects no chrome text; fields and code stay selectable. */
    .pane, .topbar, .center, .crumbs { -webkit-user-select: none; user-select: none; }
    .pane input, .pane textarea, .pane code, .pane pre { -webkit-user-select: text; user-select: text; }
    .splitter { position: fixed; top: 46px; bottom: 0; width: 7px; z-index: 25; cursor: col-resize;
      touch-action: none; }
    .splitter::after { content: ''; position: absolute; top: 0; bottom: 0; left: 3px; width: 1px; background: transparent; }
    .splitter:hover::after, .splitter.dragging::after { background: var(--accent); width: 2px; left: 2px; }
    .splitter.left { left: calc(var(--left-w) - 4px); }
    .splitter.right { right: calc(var(--right-w) - 4px); }
    .panel-reveal { position: fixed; top: 50%; z-index: 26; width: 18px; height: 56px; padding: 0; display: none;
      background: var(--panel); color: var(--muted); border: 1px solid var(--border); cursor: pointer; font-size: 12px; }
    .panel-reveal:hover { color: var(--text); border-color: var(--accent); }
    .panel-reveal.left { left: 0; border-left: none; border-radius: 0 6px 6px 0; }
    .panel-reveal.right { right: 0; border-right: none; border-radius: 6px 0 0 6px; }
    .panel-reveal.shown { display: block; }
    body.resizing, body.resizing * { cursor: col-resize !important; user-select: none !important; }
    .center { position: fixed; top: 46px; left: var(--left-w); right: var(--right-w); bottom: 0; overflow: auto;
      display: flex; align-items: flex-start; justify-content: center; padding: 28px 16px; box-sizing: border-box; }
    .frame-zoom { flex-shrink: 0; margin-top: 30px; }
    .canvas-toolbar { position: fixed; top: 54px; left: calc(var(--left-w) + 12px); z-index: 22; display: flex;
      align-items: center; gap: 2px; padding: 3px; background: var(--panel); border: 1px solid var(--border);
      border-radius: 8px; box-shadow: 0 4px 14px rgba(0,0,0,.35); }
    .canvas-toolbar .seg { background: transparent; color: var(--muted); border: 0; border-radius: 6px;
      padding: 3px 9px; cursor: pointer; font: inherit; }
    .canvas-toolbar .seg:hover { color: var(--text); background: var(--panel2); }
    .canvas-toolbar .seg.on { color: #fff; background: var(--accent2); }
    .canvas-toolbar .sep { width: 1px; height: 16px; background: var(--border); margin: 0 4px; }
    .canvas-toolbar .zoom-level { min-width: 42px; text-align: center; color: var(--text); font-variant-numeric: tabular-nums; }
    .canvas-hint { position: fixed; top: 92px; left: calc(var(--left-w) + 12px); z-index: 22; max-width: 340px;
      padding: 6px 10px; border-radius: 8px; background: var(--panel2); border: 1px solid var(--warn); color: var(--text); }
    .crumbs { position: fixed; top: 54px; right: calc(var(--right-w) + 12px); z-index: 22; display: none;
      align-items: center; gap: 2px; padding: 3px; background: var(--panel); border: 1px solid var(--accent2);
      border-radius: 8px; box-shadow: 0 4px 14px rgba(0,0,0,.35); max-width: 46vw; overflow: hidden; }
    .crumbs .crumb { background: transparent; color: var(--muted); border: 0; border-radius: 6px; padding: 3px 8px;
      cursor: pointer; font: inherit; white-space: nowrap; }
    .crumbs .crumb:hover { color: var(--text); background: var(--panel2); }
    .crumbs .crumb.here { color: var(--text); font-weight: 600; cursor: default; }
    .crumbs .crumb.done { color: #fff; background: var(--accent2); margin-left: 6px; }
    .crumbs .crumb-sep { color: var(--muted); }
    body.editing-component .frame { box-shadow: 0 12px 40px rgba(0,0,0,.5), 0 0 0 2px var(--accent2); }
    .overlay-tag { position: absolute; left: -2px; top: -18px; height: 16px; padding: 0 5px; border-radius: 3px;
      background: #2563eb; color: #fff; font: 600 10px/16px system-ui, sans-serif; white-space: nowrap; }
    .overlay-tag:empty { display: none; }
    .overlay-tag.hover { background: rgba(37,99,235,.85); }
    .overlay-tag.below { top: calc(100% + 2px); }
    .instance-ghost { position: absolute; pointer-events: none; border: 1px solid rgba(79,140,255,.45); border-radius: 3px; display: none; z-index: 4; }
    .overlay-tag.inside { top: auto; bottom: 0; left: auto; right: 0; }
    .sel-overlay.flash { animation: sel-flash .9s ease-out; }
    @keyframes sel-flash { 0% { box-shadow: 0 0 0 6px rgba(79,140,255,.55); } 100% { box-shadow: 0 0 0 0 rgba(79,140,255,0); } }
    .frame { background: #ffffff; border-radius: 18px; overflow: hidden;
      box-shadow: 0 12px 40px rgba(0,0,0,.5), 0 0 0 1px var(--border); }
    .section { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .8px;
      color: var(--muted); margin: 14px 0 6px; }
    .section:first-child { margin-top: 2px; }
    .card { background: var(--panel2); border: 1px solid var(--border); border-radius: 8px; padding: 6px; }
    .btn { background: var(--panel2); color: var(--text); border: 1px solid var(--border);
      border-radius: 6px; padding: 5px 10px; cursor: pointer; font: inherit; }
    .btn:hover { border-color: var(--accent); }
    .btn.primary { background: var(--accent2); border-color: var(--accent2); color: #fff; }
    .btn.danger:hover { border-color: var(--danger); color: var(--danger); }
    .btn.icon { padding: 5px 8px; }
    .btn:disabled { opacity: .4; cursor: default; }
    .input, .select { background: var(--bg); color: var(--text); border: 1px solid var(--border);
      border-radius: 6px; padding: 5px 8px; font: inherit; box-sizing: border-box; }
    .input:focus, .select:focus { outline: none; border-color: var(--accent); }
    .row { display: flex; gap: 6px; align-items: center; margin: 4px 0; }
    .row label { width: clamp(84px, 36%, 180px); color: var(--muted); flex-shrink: 0; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap; }
    .row .input, .row .select { flex: 1; min-width: 0; }
    textarea.input { resize: vertical; line-height: 1.35; }
    .pane.right { container-type: inline-size; }
    .prop { display: grid; grid-template-columns: minmax(0, 1fr) auto; grid-template-areas: "label actions" "ctl ctl";
      align-items: center; column-gap: 6px; row-gap: 3px; margin: 7px 0; min-height: 24px; }
    .prop > .prop-label { grid-area: label; color: var(--muted); min-width: 0; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap; }
    .prop > .prop-ctl { grid-area: ctl; display: flex; align-items: center; gap: 6px; min-width: 0; }
    .prop > .prop-actions { grid-area: actions; display: flex; align-items: center; gap: 2px; }
    .prop-ctl > .input, .prop-ctl > .select, .prop-ctl > .pill, .prop-ctl > .row { flex: 1; min-width: 0; width: 100%; }
    .prop-ctl > .row { margin: 0; }
    .prop.unset > .prop-label::after { content: " · default"; color: var(--muted); font-style: italic; opacity: .8; }
    .prop.unset > .prop-ctl { opacity: .6; }
    .prop-actions .btn.icon { min-width: 24px; height: 24px; padding: 0 5px; font-size: 12px; line-height: 1; }
    .prop.inline { grid-template-columns: clamp(84px, 38%, 150px) minmax(0, 1fr) auto; grid-template-areas: "label ctl actions"; }
    @container (min-width: 250px) {
      .prop.compact { grid-template-columns: clamp(84px, 38%, 150px) minmax(0, 1fr) auto; grid-template-areas: "label ctl actions"; }
    }
    textarea.input.grow { resize: vertical; overflow-y: auto; min-height: 30px; max-height: 240px; white-space: pre-wrap; }
    .ctl-wrap { flex: 1; min-width: 0; display: flex; flex-direction: column; }
    .color-ctl { display: flex; align-items: center; gap: 4px; min-width: 0; }
    .swatch { display: inline-block; flex-shrink: 0; border-radius: 4px; border: 1px solid var(--border); box-sizing: border-box; }
    .swatch-btn { position: relative; display: inline-flex; cursor: pointer; padding: 3px; border-radius: 6px;
      border: 1px solid var(--border); background: var(--bg); flex-shrink: 0; }
    .swatch-btn:hover, .swatch-btn:focus-within { border-color: var(--accent); }
    .swatch-picker { position: absolute; left: 0; bottom: 0; width: 1px; height: 1px; opacity: 0; padding: 0; border: 0; }
    .color-ctl .input.hex { flex: 1; min-width: 0; font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
    .color-ctl .input.alpha { width: 46px; flex: 0 0 46px; padding: 5px 4px; }
    .color-ctl .unit { margin-left: -2px; }
    .event-ctl { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
    .event-ctl .select, .event-ctl .input { width: 100%; }
    .event-ctl .input[hidden] { display: none; }
    .cond-editor { display: flex; gap: 4px; flex: 1; min-width: 0; flex-wrap: wrap; }
    .cond-editor .select { flex: 1 1 90px; min-width: 0; }
    .pill.bound { border-color: var(--accent); color: var(--accent); font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
    .bind-picker { display: flex; flex-direction: column; gap: 4px; margin: 2px 0 6px; }
    .bind-list { max-height: 180px; overflow: auto; }
    .bind-list .pal-row { cursor: pointer; }
    .pal-row.selected { background: var(--sel); outline: 1px solid var(--accent); }
    .bind-new { display: flex; flex-direction: column; gap: 4px; }
    .bind-new[hidden] { display: none; }
    .bind-preview { font-size: 11px; color: var(--good); word-break: break-all; }
    .input.invalid, .select.invalid { border-color: var(--danger); }
    .field-err { color: var(--danger); font-size: 11px; line-height: 1.3; }
    .field-err:empty { display: none; }
    .tree-row { display: flex; align-items: center; padding: 3px 6px; border-radius: 5px;
      cursor: pointer; white-space: nowrap; user-select: none; }
    .tree-row:hover { background: var(--sel); }
    .tree-row.selected { background: var(--sel); outline: 1px solid var(--accent); }
    .tree-row .t { color: var(--text); }
    .tree-row .hint { color: var(--muted); margin-left: 6px; overflow: hidden;
      text-overflow: ellipsis; max-width: 120px; }
    .tree-row.droptarget { outline: 1px dashed var(--accent); }
    .tree-row.drop-before { box-shadow: inset 0 2px 0 var(--accent); }
    .tree-row.drop-after { box-shadow: inset 0 -2px 0 var(--accent); }
    .drop-line { position: absolute; z-index: 6; pointer-events: none; background: #4f8cff; border-radius: 2px; }
    .drop-line.inside { background: rgba(79,140,255,.12); border: 2px dashed #4f8cff; box-sizing: border-box; }
    .tree-row .twisty { width: 12px; flex-shrink: 0; color: var(--muted); font-size: 10px; text-align: center; cursor: pointer; }
    .inline-edit { position: absolute; z-index: 7; box-sizing: border-box; resize: none; font: inherit; font-size: 14px;
      padding: 2px 4px; border: 2px solid #4f8cff; border-radius: 3px; background: #fff; color: #111; outline: none; }
    .pal-row { display: flex; align-items: center; padding: 3px 8px; border-radius: 5px;
      cursor: grab; user-select: none; }
    .pal-row:hover { background: var(--sel); }
    .pal-row .cat { margin-left: auto; color: var(--muted); font-size: 11px; }
    .pill { display: inline-flex; align-items: center; gap: 6px; padding: 2px 8px;
      border-radius: 99px; background: var(--panel2); border: 1px solid var(--border); color: var(--muted); }
    .dot { width: 8px; height: 8px; border-radius: 99px; background: var(--good); display: inline-block; }
    .dot.saving { background: var(--warn); }
    .dot.error { background: var(--danger); }
    .modal-back { position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 60; display: flex;
      align-items: center; justify-content: center; }
    .modal { width: 420px; max-width: calc(100vw - 40px); background: var(--panel); border: 1px solid var(--border);
      border-radius: 12px; padding: 16px; box-shadow: 0 20px 60px rgba(0,0,0,.6); display: flex; flex-direction: column; gap: 10px; }
    .modal-title { font-size: 15px; font-weight: 700; }
    .modal-problem { min-height: 16px; color: var(--warn); font-size: 12px; }
    .modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
    .topbar { overflow: hidden; }
    .topbar .select { min-width: 0; }
    @media (max-width: 1400px) {
      .topbar { gap: 6px; padding: 0 8px; }
      .topbar .brand { display: none; }
      .topbar .select { max-width: 132px; }
      .topbar .btn { padding: 5px 7px; }
      .topbar .pill { padding: 2px 6px; }
    }
    .state-scenarios { display: flex; flex-wrap: wrap; gap: 4px; margin: 2px 0 6px; }
    .chip { background: var(--bg); color: var(--muted); border: 1px solid var(--border); border-radius: 99px;
      padding: 2px 10px; cursor: pointer; font: inherit; }
    .chip:hover { color: var(--text); border-color: var(--accent); }
    .chip.on { color: #fff; background: var(--accent2); border-color: var(--accent2); }
    .state-group { font-size: 11px; color: var(--muted); margin: 8px 0 2px; text-transform: uppercase; letter-spacing: .5px; cursor: default; }
    .input.invalid { border-color: var(--danger); }
    .btn.mini { padding: 1px 6px; font-size: 11px; }
    .mod-chip { display: flex; flex-direction: column; gap: 2px; background: var(--bg);
      border: 1px solid var(--border); border-radius: 7px; padding: 6px 8px; margin: 5px 0; }
    .mod-head { display: flex; align-items: center; }
    .mod-head .x { margin-left: auto; color: var(--muted); cursor: pointer; padding: 0 4px; }
    .mod-head .x:hover { color: var(--danger); }
    .overlay { position: fixed; right: calc(var(--right-w) + 10px); bottom: 12px; width: 520px; max-height: 60%;
      background: var(--panel); border: 1px solid var(--border); border-radius: 10px;
      box-shadow: 0 16px 50px rgba(0,0,0,.6); z-index: 40; display: flex; flex-direction: column; }
    .overlay pre { margin: 0; padding: 12px; overflow: auto; font-size: 11px; color: var(--text); }
    .overlay .head .btn { margin-left: 6px; }
    .overlay .export-title { font-family: ui-monospace, monospace; font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .overlay .export-note { padding: 6px 12px; color: var(--warn); border-bottom: 1px solid var(--border); }
    .overlay .head { display: flex; align-items: center; padding: 8px 12px;
      border-bottom: 1px solid var(--border); font-weight: 600; }
    .muted { color: var(--muted); }
    ::-webkit-scrollbar { width: 10px; height: 10px; }
    ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 6px; }
    ::-webkit-scrollbar-track { background: transparent; }
  """

  internal fun installStylesheet() {
    val style = document.createElement("style")
    style.textContent = STYLE
    document.head?.appendChild(style)
  }

  internal fun el(tag: String, cls: String = "", text: String = ""): HTMLElement {
    val e = document.createElement(tag) as HTMLElement
    if (cls.isNotEmpty()) e.className = cls
    if (text.isNotEmpty()) e.textContent = text
    return e
  }

  internal fun button(label: String, cls: String = "btn", onClick: () -> Unit): HTMLElement {
    val b = el("button", cls, label)
    b.addEventListener("click", { _ -> onClick() })
    return b
  }

  internal fun input(cls: String = "input"): HTMLInputElement =
    (document.createElement("input") as HTMLInputElement).also { it.className = cls }

  internal fun textarea(cls: String = "input"): HTMLTextAreaElement =
    (document.createElement("textarea") as HTMLTextAreaElement).also { it.className = cls }

  internal fun select(cls: String = "select"): HTMLSelectElement =
    (document.createElement("select") as HTMLSelectElement).also { it.className = cls }

  internal fun section(title: String): HTMLElement = el("div", "section", title)

  internal fun clear(host: HTMLElement) {
    while (host.firstChild != null) host.removeChild(host.firstChild!!)
  }

  /** Transient bottom-center notice (V2 M1: op rejections / rebase). */
  internal fun toast(message: String) {
    val t = el(
      "div",
      "",
      message,
    )
    t.setAttribute(
      "style",
      "position:fixed; bottom:18px; left:50%; transform:translateX(-50%); z-index:99; " +
        "background:var(--panel, #222); color:var(--fg, #eee); border:1px solid var(--line, #444); " +
        "border-radius:8px; padding:8px 14px; font-size:12px; box-shadow:0 4px 14px rgba(0,0,0,.4);",
    )
    document.body?.appendChild(t)
    window.setTimeout({ t.remove(); null }, 2600)
  }
}
