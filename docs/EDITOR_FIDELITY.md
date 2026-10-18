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

- Entries are exact lowercase host names of at least two labels: no scheme,
  port, path or wildcard; a subdomain must be listed itself. Refused at config
  load: `localhost` and `*.localhost`, single-label names, IP literals in any
  form (IPv6; IPv4 dotted, short-dotted, decimal, octal or hex). The IPv4
  test is the WHATWG URL rule browsers use: a name whose last label is all
  digits or `0x` hex is an address (`127.1`, `2130706433`, `0177.0.0.1`,
  `0x7f.0.0.0x1`, `img.123`); a numeric label anywhere else is a name
  (`1.bp.blogspot.com`, `0.gravatar.com` are accepted). Absent or empty =
  off (`/preview-asset` answers 404).
- The editor reads the list from `GET /preview-assets` and rewrites a URL whose
  host is listed, on its scheme's default port, to
  `GET /preview-asset?url=<encoded URL>`; any other URL loads directly as
  before. If the list never arrives, the first image waits for it at most 3 s
  and later images do not wait. The editor runs at most two image fetches
  through the relay at once (the browser keeps about six connections to the
  relay and the editor's event stream holds one), so images waiting on a
  slow host leave connections free for edits and write-back; images loaded
  directly are not limited.
- The relay checks the caller's address is loopback. The relay listens on
  loopback by default; with `KELIVER_RELAY_BIND=all` (KNOWN_BUGS U35) this
  check still keeps other machines on the network off the endpoint. It does
  not stop a web page in the developer's own browser, which also connects
  from loopback; the relay's Host/Origin gate is what refuses those
  (KNOWN_BUGS U36).
- GET only, for `http`/`https` URLs without user-info, on the scheme's default
  port (80/443; another port is refused, and a malformed one is refused too:
  400 when out of range, 403 when it is not a number). It forwards
  nothing from the caller (no cookies, no `Authorization`, no other header)
  and sends a fixed `User-Agent`. It follows at most five redirects itself,
  each only to a listed host on a default port, and never from `https` down to
  `http`.
- One 10 s deadline covers the whole request, every redirect included. Each
  request runs on the endpoint's own small pool (4 threads, 16 waiting), not
  on the thread that serves the relay's other endpoints, so a slow image host
  does not hold up the relay's handling of edits or write-back; when the pool
  is full the endpoint answers `503`. (In the browser, the editor's cap of two
  relay image fetches is what keeps connections free for those requests.)
- A failure is remembered for 30 s for its URL, so a re-render does not ask
  again. The host (with its port) is remembered too only when no connection
  to it could be made: the name did not resolve, the connection was refused
  or had no route, the connect timed out, or the TLS handshake failed; for
  30 s no other URL on it is tried. A host that accepted the connection is
  never marked: the 10 s deadline running out (while waiting or while
  reading the body), a body that breaks off or a protocol error costs that
  URL only, so one slow image does not fail the other images on its host. A
  redirect target the deadline left no time to contact is not marked
  either.
- It serves only a `200` with exactly one `Content-Type` naming one of
  `image/png`, `image/jpeg`, `image/gif`, `image/webp`, `image/avif`,
  `image/bmp`, `image/x-icon`, `image/vnd.microsoft.icon` (parameters ignored;
  a comma-separated list is refused), up to 10 MB, and sends that type as
  named here, never the upstream's string. SVG is refused because the
  response comes from the relay's own origin: opened directly, an SVG is an
  active document that could call the relay's write endpoints (the canvas
  could not decode it anyway). Responses carry `X-Content-Type-Options:
  nosniff` and a sandbox `Content-Security-Policy`.
- CORS is the relay's access gate's, as for every endpoint: a loopback origin
  (`http://localhost:8096`, `http://127.0.0.1:…`) is echoed in
  `Access-Control-Allow-Origin`; any other origin is refused before the fetch.
- Images are cached in memory (LRU, 256 entries / 64 MB, ten minutes).
- Refusals are logged with the host and the reason, never the URL's path or
  query.
- DNS is trusted: the relay does not check what address a listed name
  resolves to, so a listed name that resolves to a private or loopback
  address is fetched. Listing a host says you trust it and its DNS; list only
  hosts you would let the relay fetch from.
- Preview only. Devices load images themselves and never use this.

The relay reads `keliver.portal.json` at start; restart it after changing the
list.
