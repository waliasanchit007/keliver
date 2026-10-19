# keliver-portal-tools 0.3.11 — release notes

**Status: RELEASED 2026-10-19**, on the owner's explicit go.

| | |
|---|---|
| tag | `portal-tools-v0.3.11` (annotated, object `072e8507`) → **`ebbb24c9649d42d7f15d11165031dbe733905056`** |
| release | [portal-tools-v0.3.11](https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.11), published 2026-10-19T08:29:35Z, `--latest=false` |
| zip asset | `keliver-portal-tools-0.3.11.zip`, 91,311,273 bytes, sha256 **`ae84cb2c5442948dbd8272a5a9f0d1f0d9bf42377475168d3f9c31ab1f26bddf`**, uploaded from the retained artifact `12170000839` of build run 39214066995 |
| `.sha256` asset | 98 bytes, sha256 `0b8d8d72bbf69805331eee4aa52622baff05e63b00c8ee04779020a3e4763c00` |
| tag push | ran only `portal-tools.yml` (run 39222008390, the read-only rebuild; its artifact is not attached) |

How the stored asset was checked: before publishing, the draft zip asset was
downloaded back in full and its sha256 was identical to the verified file
(`ae84cb2c…`); GitHub's server `digest` and `size` for the zip matched it
too. After publishing, the public download was checked from GitHub-hosted
runners by the reference app's move to 0.3.11 (#128, merged as `5d75b0bf9`):
each run's first step downloads the public asset and checks it against the
release's own `.sha256`, against the pinned hash and against `VERSION.json`'s
source commit.

| run (#128 head `cf986751c`) | host scaffolders and `keliver-publish` from | result |
|---|---|---|
| reference-app 39234198214 (pull request) | this checkout | prepare 34/0, `keliver-publish` self-test 41/0, device 171/0 |
| ios-host 39234198413 (pull request) | this checkout | iOS host scaffolder self-test 71/0, `ios.sh` 159/0 |
| reference-app 39255263700 (by hand) | the published zip's `bin/` | prepare 34/0, `keliver-publish` self-test 41/0, device 171/0 |
| ios-host 39255268701 (by hand) | `ios.sh`: the published zip's `bin/`; the scaffolder self-test: this checkout's | self-test 71/0, `ios.sh` 159/0 |

In every run the app itself was created by the published zip's `keliver-init`,
and the test harness (`ci/`, the isolation guard) is always this checkout's.
The runs started by hand are the published-only proof for the scaffolders
under test, with one exception: the iOS host scaffolder's self-test step
always runs this checkout's `scripts/keliver-new-ios-host.sh` (it does not
follow `KELIVER_SCAFFOLD_FROM`). At `ebbb24c96` that script is byte-identical
to the zip's `bin/keliver-new-ios-host.sh` (`git diff ebbb24c96 cf986751c --
scripts/` is empty), so the same bytes were tested; the provenance is the
checkout's.

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

| | |
|---|---|
| Source commit | `ebbb24c9649d42d7f15d11165031dbe733905056` |
| Zip | `keliver-portal-tools-0.3.11.zip`, sha256 `ae84cb2c5442948dbd8272a5a9f0d1f0d9bf42377475168d3f9c31ab1f26bddf` |
| `VERSION.json` | tools 0.3.11, source commit as above, 0 dirty files, Maven dependency 0.3.4 |
| Bundled host APK | sha256 `a3a7743e20c63a67a72351f0f2297037f4ef6029c92232e03f053390f58ca285`, no `assets/portal_ed25519.pub` |
| Build | run 39214066995 (dispatched from `release/portal-tools-0.3.11`), retained artifact `12170000839` (`keliver-portal-tools-0.3.11-ebbb24c96…`, artifact digest `sha256:0edc4a8b…`, expires 2026-11-18) |
| Device job | run 39216352757 (dispatched from `release/portal-tools-0.3.11`, pinned to the build's APK `a3a7743e…`): 19/0 and 28/0, on GitHub's emulator |

The build job's checks, on the packaged bundle (Linux): adopter acceptance
25/0, the identity gate (foreign-relay refusal, U21) 6/0, store identity 11/0,
store recovery through the packaged commands 144/0, device-host packaging
hygiene (U22) 10/0, guest bundle signing 4/0, `keliver-publish` 41/0, private
key permissions (U27) 63/0, and the production-host (68/0, 62/0), iOS host
(65/0, 65/0) and publish (51/0, 33/0) scaffolders from `scripts/` and from the
package.

Step 3 at the source commit: a `portal-tools-v0.3.11` tag runs only
`portal-tools.yml`, which has no write permission; `publish.yml` takes `v*`
only. No workflow was disabled, so step 5 restored nothing.

Local pre-gates on the releasing machine (not run on the attached bytes; the
build is not byte-reproducible):

- `keliver-store-identity-repro.sh`: 11/0;
- `keliver-store-recovery-check.sh`: 134 passed, 1 failed (135 checks). The
  failure, C5, needed port 8096, which a portal already running on that
  machine held. The same script passed 144/0 in the build job above (Linux,
  144 checks);
- `keliver-adopter-acceptance.sh` and `keliver-acceptance-identity-check.sh`
  were not run locally (a running portal held 8077 and 8096); they ran in the
  build job (25/0, 6/0).

**Not run for this release:** `keliver-device-verify.sh` on a physical phone.
The production refusals and the development route were checked on GitHub's
emulator only (the device job above).
