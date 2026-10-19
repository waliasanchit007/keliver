# keliver-portal-tools 0.3.11 — release notes

**Status: CANDIDATE — not released.** Tag and publish only on the owner's
explicit go, following [`PORTAL_TOOLS_RELEASE.md`](PORTAL_TOOLS_RELEASE.md).

| | |
|---|---|
| tag | `portal-tools-v0.3.11` → *(the verified candidate's `VERSION.json` `sourceCommit`; not tagged)* |
| release | *(none yet)* |
| zip asset | `keliver-portal-tools-0.3.11.zip` *(the retained, verified CI artifact; not uploaded)* |
| `.sha256` asset | *(not uploaded)* |

What is in it since 0.3.10 (`78bcfa0ad`): PRs #120 (K1), #121 (K2), #122
(U35/U36), #123 (K5), #124 (K3), #125 (K5 follow-up) and #126 (K4). #118 (the
reference app on the published 0.3.10) and #119 (start-up measurements)
change nothing in the bundle.

**New apps still get the 0.3.4 libraries** (`dev.keliver:*:0.3.4`, the newest
on Maven Central): `keliver-init` and every scaffolder wire the same version
as in 0.3.10, and an existing app keeps the version its `keliver.portal.json`
names in `appRuntime.keliverVersion`. Nothing in this release changes the
guest, host or widget libraries, or the host templates. The relay, MCP server
and editor in this bundle carry their own portal jars, built from this
release's source; no app compiles against them.

## Security

- **The relay and the editor's file server listen on this machine only**
  (KNOWN_BUGS U35). They listened on every network interface, so anyone who
  could reach the machine could read the relay and use its edit, write-back
  and publish endpoints. `KELIVER_RELAY_BIND=all` opts the relay in to every
  interface (a physical phone on the same Wi-Fi); the relay and
  `keliver-portal` then print a warning, and any other value is refused. The
  relay also refuses to start when something already answers on its port.
  Emulators (`10.0.2.2`), simulators and `adb reverse` arrive on loopback and
  are unaffected.
- **A web page in your browser can no longer drive the relay** (KNOWN_BUGS
  U36). Every endpoint answered `Access-Control-Allow-Origin: *` and nothing
  checked `Host`, so any page on any site, or a DNS-rebinding page, could read
  the relay and call its write endpoints. Every request now passes one check:
  `Host` must name this machine (`localhost`, a loopback address, the
  emulators' `10.0.2.2` / `10.0.3.2`, or the address the connection arrived
  on); a request with an `Origin` must come from a loopback origin (echoed,
  never `*`); a request a browser marks cross-site is refused. Refusals are
  403. `/undo` and `/redo` take POST only.

Both affect every tools release up to and including 0.3.10.

## Behaviour changes

- **A phone on your network reaching the relay** (a debug host built with
  `--bundle-server http://<your LAN address>:8077`) needs
  `KELIVER_RELAY_BIND=all` when you start the portal.
- **A tunnel or proxy in front of the relay** that passes its own host name
  through is refused (U36). Serve a published directory (`keliver-publish`)
  through the tunnel instead of the relay.
- **An editor opened from a non-loopback address** (the machine's LAN name or
  address) is refused by the relay; open it from `localhost`.
- **Write-back no longer silently replaces author code.** When a change could
  not be made in place it used to fall back to a full export of the screen,
  unchecked: comments, helper functions and the bindings parameter name were
  lost, and RawCode was written as a call that does not compile. Now the full
  export is written only when it is lossless and reads back as the document;
  otherwise the change is **refused**, the file is left as it is, and the
  editor shows the screen's write-back status (`surgical`, `export`,
  `created`, `refused`, `failed`; `writeBack` on `/doc-events` and
  `/devstate`) as a banner. An edit whose write-back would be refused is now
  refused when it is made, with the reason, instead of blocking every later
  edit of the screen.
- **A text template with references is grammar again.** 0.3.10 made a widget
  whose literal argument is a template RawCode (U33). A template whose
  embedded expressions are bindings, item fields, component parameters or
  design tokens is now editable (see "New"); any other template stays RawCode.
- A changed screen file is no longer merged by statement position: write-back
  merges only onto the text it last wrote or read; otherwise it reads the file
  and reports the editor's unwritten change as `discarded`.

## New in the editor (portal grammar)

Source that made a component opaque or a call RawCode is now editable, and
writes back surgically (only the changed part; a no-op is byte-identical):

- **Layout (K1):** a layout widget's `margin = Margin(…)` and the scoped
  modifiers `flex`, `grow`, `shrink`, `margin`, `width`, `height`,
  `size(Dp, Dp)`, `horizontalAlignment`, `verticalAlignment`, offered only
  under a Row/Column/Box they fit. Modifier chains keep their author order.
  `Dp(16)` (an Int, which does not compile) is now RawCode; a repeated
  modifier in one chain and a Double modifier argument written as an integer
  are RawCode instead of being coerced.
- **Design tokens (K2):** a reference to a constant in the app's own sources
  (`colorArgb = C.Ink`, `url = Assets.Logo`), where source takes a plain
  literal of the token's type. Tokens are read from the screens and components
  directories and an optional `tokensDirs` in `keliver.portal.json`. The editor
  shows a token chip with a picker and "detach"; the MCP server adds
  `list_tokens`.
- **Conditions (K3):** conditional children (`if (!b.x) { … } else { … }`)
  and conditional values (`colorArgb = if (selected) C.Ink else C.Muted`,
  `onClick = if (enabled) onTap else null`), testing a field as a Boolean, a
  String for emptiness or blankness, or a nullable component parameter for
  null.
- **String templates and lists (K4):** `"Rating: ${b.rating}/${C.Max}"` where
  each embedded expression is a binding, an item field, a component parameter
  or a token, and `listOf(…)` values of literals and references on
  `List<Int>`/`List<String>` props. A template never reads a `Double` or
  nullable value (a device and the preview print them differently): such a
  call stays RawCode.

## New on the canvas (K5)

- **App images:** list your image hosts in `keliver.portal.json`
  `previewAssetHosts` and the canvas loads their images through the relay
  (`GET /preview-asset`), for hosts that send no CORS headers. Exact host
  names only (no `localhost`, single labels or IP literals); raster types only,
  10 MB cap, one deadline per request, its own bounded pool, per-host fetch
  limits, and a short memory of failing hosts. Empty or absent = off.
- **Component modifiers:** a component that declares `modifier: Modifier` and
  passes it to its root is now grammar, and an instance's modifiers apply to
  that root.
- A "Large phone 412×915" frame preset; a StyledText's Bold box reflects an
  explicit `weight`.
- The app's theme on the canvas (`AppPreviewEntry.screenWrapper` /
  `hostWrapper`) is for per-app editors and needs a library release after
  0.3.4: an editor scaffolded on 0.3.4 (`keliver-new-editor.sh`) carries those
  lines commented out, and they do not compile against 0.3.4.

## Fixed

- A statement inserted after the last statement of an `if` block was written
  after the block's closing brace; two or more statements inserted in a row
  were written in reverse order. Every surgical write is now read back and
  compared with the document before it is written.
- A CRLF screen or component was not recognized, and the relay's next write
  replaced it with an LF full export; it is now read and written back CRLF.
- The editor no longer wedges when an ops request gets no answer, and keeps an
  edit (and refetches) when a reply cannot be read.
- An opaque component's placeholder drew its name over the "opaque" reason.
- The editor saw project components without parameter types, so it refused
  modifiers for components that accept them.

## Older editors against this relay

A per-app editor built on the 0.3.4 libraries (`dev.keliver:portal-editor`)
keeps working against this release's relay: the relay sends tokens,
conditionals, templates and lists only to a client that asks for them, and an
older editor gets the literal value, the true branch, the text and the
resolved list. Edit those values in this bundle's editor (or a per-app editor
on a later library release).

## Candidate verification

*(to be filled from the verified CI build, as for 0.3.10)*

| | |
|---|---|
| Source commit | |
| Zip | `keliver-portal-tools-0.3.11.zip`, sha256 |
| Bundled host APK | sha256 , no `assets/portal_ed25519.pub` |
| Build | run , retained artifact |
| Device job | run |

Local pre-gates on a local build of the candidate commit (a local build is
not the bytes to attach; the build is not byte-reproducible):

- see the release branch's commit log and the preparation report.
