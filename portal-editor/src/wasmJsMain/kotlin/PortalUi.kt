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

object Ui {
  const val STYLE = """
    :root {
      --bg: #16171b; --panel: #1f2026; --panel2: #26272e; --border: #34353e;
      --text: #e6e7ec; --muted: #9a9ba6; --accent: #8b7cf7; --accent2: #6f5ff0;
      --good: #4cc38a; --warn: #f0a05f; --danger: #e5534b; --sel: #2f3040;
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
    .pane.left { left: 0; width: 272px; border-right: 1px solid var(--border); padding: 10px; }
    .pane.right { right: 0; width: 300px; border-left: 1px solid var(--border); padding: 10px; }
    .center { position: fixed; top: 46px; left: 272px; right: 300px; bottom: 0; overflow: auto;
      display: flex; align-items: flex-start; justify-content: center; padding: 28px 16px; box-sizing: border-box; }
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
    .row label { width: 96px; color: var(--muted); flex-shrink: 0; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap; }
    .row .input, .row .select { flex: 1; min-width: 0; }
    .tree-row { display: flex; align-items: center; padding: 3px 6px; border-radius: 5px;
      cursor: pointer; white-space: nowrap; user-select: none; }
    .tree-row:hover { background: var(--sel); }
    .tree-row.selected { background: var(--sel); outline: 1px solid var(--accent); }
    .tree-row .t { color: var(--text); }
    .tree-row .hint { color: var(--muted); margin-left: 6px; overflow: hidden;
      text-overflow: ellipsis; max-width: 120px; }
    .tree-row.droptarget { outline: 1px dashed var(--accent); }
    .pal-row { display: flex; align-items: center; padding: 3px 8px; border-radius: 5px;
      cursor: grab; user-select: none; }
    .pal-row:hover { background: var(--sel); }
    .pal-row .cat { margin-left: auto; color: var(--muted); font-size: 11px; }
    .pill { display: inline-flex; align-items: center; gap: 6px; padding: 2px 8px;
      border-radius: 99px; background: var(--panel2); border: 1px solid var(--border); color: var(--muted); }
    .dot { width: 8px; height: 8px; border-radius: 99px; background: var(--good); display: inline-block; }
    .dot.saving { background: var(--warn); }
    .mod-chip { display: flex; flex-direction: column; gap: 2px; background: var(--bg);
      border: 1px solid var(--border); border-radius: 7px; padding: 6px 8px; margin: 5px 0; }
    .mod-head { display: flex; align-items: center; }
    .mod-head .x { margin-left: auto; color: var(--muted); cursor: pointer; padding: 0 4px; }
    .mod-head .x:hover { color: var(--danger); }
    .overlay { position: fixed; right: 310px; bottom: 12px; width: 520px; max-height: 60%;
      background: var(--panel); border: 1px solid var(--border); border-radius: 10px;
      box-shadow: 0 16px 50px rgba(0,0,0,.6); z-index: 40; display: flex; flex-direction: column; }
    .overlay pre { margin: 0; padding: 12px; overflow: auto; font-size: 11px; color: var(--text); }
    .overlay .head { display: flex; align-items: center; padding: 8px 12px;
      border-bottom: 1px solid var(--border); font-weight: 600; }
    .muted { color: var(--muted); }
    ::-webkit-scrollbar { width: 10px; height: 10px; }
    ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 6px; }
    ::-webkit-scrollbar-track { background: transparent; }
  """

  fun installStylesheet() {
    val style = document.createElement("style")
    style.textContent = STYLE
    document.head?.appendChild(style)
  }

  fun el(tag: String, cls: String = "", text: String = ""): HTMLElement {
    val e = document.createElement(tag) as HTMLElement
    if (cls.isNotEmpty()) e.className = cls
    if (text.isNotEmpty()) e.textContent = text
    return e
  }

  fun button(label: String, cls: String = "btn", onClick: () -> Unit): HTMLElement {
    val b = el("button", cls, label)
    b.addEventListener("click", { _ -> onClick() })
    return b
  }

  fun input(cls: String = "input"): HTMLInputElement =
    (document.createElement("input") as HTMLInputElement).also { it.className = cls }

  fun select(cls: String = "select"): HTMLSelectElement =
    (document.createElement("select") as HTMLSelectElement).also { it.className = cls }

  fun section(title: String): HTMLElement = el("div", "section", title)

  fun clear(host: HTMLElement) {
    while (host.firstChild != null) host.removeChild(host.firstChild!!)
  }

  /** Transient bottom-center notice (V2 M1: op rejections / rebase). */
  fun toast(message: String) {
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
