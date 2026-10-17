# Editor canvas fidelity — what matches the phone, and what does not

The editor canvas draws a screen with the **same Keliver widget code a phone
runs**: the screen document is interpreted by the generated `RenderNode`
(portal-render) into the keliver-material guest composables, and their
protocol changes are applied to the keliver-material **Compose UI** host
widgets, compiled to wasm. So layout, widget behaviour and every widget
property are the device's own code. What can still differ is what lives
*around* the screen on a device, and what the browser cannot do.

## Matches the device

| Area | How |
|---|---|
| Widgets, layout, modifiers | Same guest composables and Compose UI host widgets; scoped layout modifiers apply only in the parent scope a device has (K1). Parity tests compare the interpreted tree with the compiled one (`portal-render` wasm tests). |
| A `Theme` in the screen | The `Theme` widget is rendered like any other widget; its colours reach the widgets inside it (`CanvasFidelityTest`). |
| The app's theme around every screen | On a device the app's root usually wraps every route in a `Theme(...)`, and the native host's `MaterialTheme` supplies every colour role that `Theme` leaves unset (a Slider's inactive track, container colours…). A per-app editor declares both on its `AppPreviewEntry`: `screenWrapper` (guest side, e.g. `PreviewWrapper { content -> AppTheme { content() } }`) and `hostWrapper` (host side, e.g. `PreviewWrapper { content -> MaterialTheme(appColorScheme) { content() } }`). Without them the canvas uses Material's default scheme for unset roles — the default lavender. `scripts/keliver-new-editor.sh` shows where they go. |
| Images on the app's own hosts | List the hosts in `keliver.portal.json` `previewAssetHosts`; the canvas loads their images through the relay's `/preview-asset` (see below). Other hosts load directly from the browser and need CORS. |
| Modifiers passed to a component | A component that declares `modifier: Modifier` and passes it to its root (`Column(modifier = modifier)` or `modifier.padding(8)`) is portal grammar; an instance's modifiers (`MyCard(modifier = Modifier.flex(1.0))`) go on that root first, then the body's own, in the instance's parent scope — as the compiled call does. |
| Text weight | `weight` in 100..900 decides the weight on a device whatever `bold` says; the properties panel's Bold box shows that (checked from 600) and is disabled while `weight` is set. |

## Still differs

- **Fonts.** The canvas draws text with the typeface embedded in Compose
  Multiplatform's web runtime: the editor loads no font files, so neither the
  browser's fonts nor the app's custom fonts are used. Android draws the
  platform default (Roboto unless the host's typography names another font);
  iOS draws the system font (San Francisco). Line breaks and text widths can
  therefore differ slightly, and characters outside the embedded font
  (emoji, many non-Latin scripts) may not render on the canvas.
- **The native app around the screen.** Status bar, navigation bar, the host's
  toolbar, window insets, the keyboard, and any native view the host draws
  around the Keliver view are not on the canvas.
- **Frame size.** The frame presets are fixed sizes in dp (1 CSS px = 1 dp on
  the canvas): Phone 390×780, Large phone 412×915 (a large modern phone's
  default), Tablet 768×900, Web 1080×760. A real device's usable height is its
  screen minus the system bars, and its density and font scale are the user's;
  neither is simulated.
- **Host-side widget overrides.** A host that replaces a keliver-material widget
  with its own (its own `Theme`, sheet, button…) draws that widget its own way;
  the canvas draws keliver-material's. A per-app editor can mirror only what
  its `hostWrapper` can express.
- **SVG images.** The canvas has no SVG decoder, and the relay does not serve
  `image/svg+xml` (below).
- **A modifier set both by a caller and by the component's own chain**
  (`MyCard(modifier = Modifier.padding(4))` on a root with `modifier.padding(8)`)
  previews once, with the caller's value; a device applies both.
- **Generic editor.** The tools bundle's generic editor has no app code, so it
  has no `screenWrapper`/`hostWrapper` and no real presenters; build a per-app
  editor (`keliver-new-editor.sh`) for an app-themed canvas.

## Images through the relay (`previewAssetHosts`)

The canvas fetches `AsyncImage` URLs with the browser's `fetch()`, from a page
on localhost. An image host that sends no `Access-Control-Allow-Origin` cannot
be read there. For hosts the app lists, the relay fetches instead:

```json
"previewAssetHosts": ["img.example.com"]
```

- Entries are exact lowercase host names: no scheme, port, path or wildcard,
  no IP literal; a subdomain must be listed itself. Absent or empty = off
  (`/preview-asset` answers 404).
- The editor reads the list from `GET /preview-assets` and rewrites a URL whose
  host is listed to `GET /preview-asset?url=<encoded URL>`; any other URL loads
  directly as before.
- The relay answers loopback callers only (its listening socket accepts every
  interface, so the endpoint checks the caller's address itself), GET only, for `http`/`https` URLs
  without user-info. It forwards nothing from the caller (no cookies, no
  `Authorization`, no other header) and sends a fixed `User-Agent`. It follows
  at most five redirects itself, each only to a listed host.
- It serves only a `200` with an `image/*` type other than `image/svg+xml`, up
  to 10 MB, with connect/read/call timeouts. SVG is refused because the
  response comes from the relay's own origin: opened directly, an SVG is an
  active document that could call the relay's write endpoints (the canvas
  could not decode it anyway). Responses carry `X-Content-Type-Options:
  nosniff` and a sandbox `Content-Security-Policy`.
- `Access-Control-Allow-Origin` names the caller's origin only when that origin
  is loopback (`http://localhost:8096`, `http://127.0.0.1:…`).
- Images are cached in memory (LRU, 256 entries / 64 MB, ten minutes).
- Refusals are logged with the host and the reason, never the URL's path or
  query.
- An allowlisted host name is trusted as configured: the relay does not check
  what address it resolves to. List only hosts you would let the relay fetch
  from.
- Preview only. Devices load images themselves and never use this.

The relay reads `keliver.portal.json` at start; restart it after changing the
list.
