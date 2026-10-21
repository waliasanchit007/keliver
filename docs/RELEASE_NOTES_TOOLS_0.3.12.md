# keliver-portal-tools 0.3.12 — release notes

**Status: RELEASED 2026-10-21**, on the owner's explicit go.

| | |
|---|---|
| tag | `portal-tools-v0.3.12` (annotated, object `1c6f0c31`) → **`8139a55beb77b1a874009ded5d4753c47582c1c9`** |
| release | [portal-tools-v0.3.12](https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.12), published 2026-10-21T10:26:57Z, `--latest=false` |
| zip asset | `keliver-portal-tools-0.3.12.zip`, 91,616,631 bytes, sha256 **`4c9aac7453114718dc96483de709e8dcc47e7e5dc98ba6afc82c9b1373783d42`**, uploaded from the retained artifact `12329584134` of build run 39564359560 |
| `.sha256` asset | 98 bytes, sha256 `1c3125145f2a759eda4a9ce39609a13a8e96ac03df9c2d66bcb77db5fdec41c4` |
| tag push | ran only `portal-tools.yml` (run 39584468151, the read-only rebuild; its artifact is not attached) |

How the stored asset was checked: before publishing, both draft assets were
downloaded back in full and their sha256 were identical to the verified files
(zip `4c9aac74…`, `.sha256` `1c312514…`); GitHub's server `digest` and `size`
for both matched too, and the tag was re-checked at `8139a55`. After
publishing, the public URL was downloaded on the releasing machine and hashed
identical (`4c9aac74…`). The public download from GitHub-hosted runners is
checked by the reference app's move to 0.3.12 (see below).

What is in it since 0.3.11 (`ebbb24c96`): PRs #130 (relay tests use their
own store), #131 (K6), #132 (compiled component preview), #134 (K7), #135
(U37) and #136 (the 0.4.0 libraries). #127, #128, #129, #133 and #137 record
releases and evidence and change nothing in the bundle.

**New apps get the 0.4.0 libraries** (`dev.keliver:*:0.4.0`, on Maven Central
since 2026-10-20 21:13 UTC): `keliver-init`, `keliver-new-editor.sh`,
`keliver-new-device-target.sh` and the production and iOS host scaffolders
default to 0.4.0. An existing app keeps the version its `keliver.portal.json`
names in `appRuntime.keliverVersion`; moving it is your change to make.
0.4.0 changes only the portal libraries (`portal-core`, `portal-document`,
`portal-render`, `portal-editor`): the guest, host and widget libraries are
the 0.3.4 code under a new version, and the Zipline wire format and compiled
guests are unchanged. Code that matches exhaustively on the portal document
model (`PropValue`) must handle the new subtypes; see the 0.4.0 entry of
`CHANGELOG.md`.

## Fixed

- **Contract write-back could break a screen's compile** (KNOWN_BUGS U37). When
  a screen's Bindings interface extends another interface, write-back
  declared in the screen's interface each inherited field or action the screen
  read (`val title: String get() = ""`, `fun open() {}`): a redeclaration
  without `override`, which does not compile. A supertype in the same file had
  the same effect. Write-back now counts a same-file supertype's members as
  declared and adds nothing to an interface that extends a type from another
  file. A field or action the editor cannot vouch for (the interface is not
  read whole and the file neither declares nor reads the field) is refused
  when the edit is made, with the reason. On a screen whose interface is in
  another file, this refuses a plain binding of a field the file does not
  read, which 0.3.11 allowed. Every tools release since contract write-back
  was added is affected.

## New in the editor (portal grammar)

Source that made a component opaque or a call RawCode is now portal grammar:
the component stays transparent, the canvas draws it, and edits around it
write back surgically (only the changed part; a no-op is byte-identical):

- **Equality and compound conditions, derived Boolean locals (K6):**
  `colorArgb = if (b.kind == "card" && b.open) C.Ink else C.Muted`,
  `if (b.tier == C.Top || b.flagged) { … }`, and in a component body
  `val active = enabled && !loading` read as `if (active)` / `if (!active)`.
  An equality is `field == <operand>` or `!=`, with the field on the left and
  a String, Int or Boolean literal or design token of the field's declared
  kind; terms join with `&&` only or `||` only. A derived local reads only
  parameters and is inlined where it is read. Mixed joins, parentheses,
  negated groups, field-vs-field and nullable or mismatched fields stay
  RawCode with a diagnostic. The editor shows such a condition read-only and
  edits its branches; change the condition itself in code (or as an op, through
  the MCP server). A condition test the field's declared type cannot take
  (`if (b.count)` with `count: Int`) is now refused as an edit and read as
  code; before, it could be written.
- **Int arithmetic and Ints as Dps (K7):** `cornerRadiusDp = size / 2`,
  `fontSize = b.size * 2 / 5`, `widthDp = b.columns * 48 + 8`,
  `Spacer(height = Dp(gap.toDouble()))` and `(b.gap + 4).dp`, where source
  takes an Int or a Dp. Operands are decimal Int literals, Int design tokens
  and Int bindings, parameters or item fields, under `+ - * / %` and
  parentheses, with Kotlin's precedence and 32-bit overflow; `/` and `%`
  divide only by a non-zero literal. A derived Int local (`val half = size / 2`)
  is inlined where it is read. The editor shows an expression read-only as its
  source and can replace it whole with a value or a binding; change its shape
  in code (an op through the MCP server can change single operands, and
  write-back then rewrites only those, keeping the author's parentheses and Dp
  spelling). Expressions of literals only, division by a non-literal, `-x` on
  a reference, calls, Long/Double/Float/nullable operands, and arithmetic in a
  modifier argument or a Margin side stay RawCode.
  `x.uppercase()` / `lowercase()` stay RawCode on purpose: a device runs
  Kotlin/JS in QuickJS, whose case tables a browser preview cannot vouch for.

The canvas evaluates both with the screen's mocks and an instance's
arguments. Compiled-vs-interpreted parity is pinned by tests: K6's on the
editor's target (wasm), K7's on wasm and on js (the device guest's target).
The bundled MCP server asks for both (`extendedConditions=1`,
`arithmetic=1`), its op schema documents them, and find-usages reads an
expression's operands.

## New on the canvas: compiled component preview

An **opaque** project component (its body is outside the portal grammar) used
to preview as a placeholder. A per-app editor built on the 0.4.0 libraries
(`keliver-new-editor.sh`) can now draw it with the app's own compiled
composable: compile the components into the editor (pass their directory to
`keliver-new-editor.sh`), add a `compiledComponents` block to
`keliver.portal.json` (`out`, and optionally `packageName`, `valName`,
`sameModule`) and `override val compiledComponents = AppCompiledComponents`
to the editor's `AppPreviewEntry` (the scaffold shows both, commented).
`keliver-portal` then writes the adapters file from the components'
signatures just before it builds the app's editor; restart it after adding,
removing or changing the signature of a component, or when one becomes
opaque or transparent. Transparent components keep their editable view; a compiled
component that throws while composing falls back to its placeholder.
Without the block nothing changes.

This bundle's generic editor, served for an app without its own editor,
carries no adapters: opaque components stay placeholders there. (Adapters
are looked up by component name; the generic editor is built with
`-Pkeliver.genericEditor=true` so that Keliver's own sample components can
never be drawn in place of an app's same-named ones.) The stand-alone
generator (`:portal-relay:compiledComponentAdapters`) is a task in the
Keliver repository; `keliver-portal` runs the same generator from the
relay's jars.

## Older editors against this relay

A per-app editor built on the 0.3.4 libraries keeps working against this
release's relay: the relay sends extended conditions and arithmetic only to a
client that asks for them (`extendedConditions=1`, `arithmetic=1`). An older
editor is served the true branch of an extended condition, the literal an
expression computes from literals and tokens, and no value for an expression
that reads a binding; its edits to other props keep the author's expression.
The device tree carries an extended condition under a new key and a
computable expression's value under the usual keys, so an installed guest
built before 0.4.0 renders the true branch or the value (or the prop's
default), never a wrong number. Edit those values in this bundle's editor or
a per-app editor on 0.4.0.

## For contributors

The relay's tests no longer use the developer's real portal store
(`~/.keliver-portal`): the test task points `PORTAL_STORE` at a directory
under `build/` (#130). A marker an earlier test run left in the real store is
not removed automatically.

## Candidate verification

| | |
|---|---|
| Source commit | `8139a55beb77b1a874009ded5d4753c47582c1c9` |
| Zip | `keliver-portal-tools-0.3.12.zip`, sha256 `4c9aac7453114718dc96483de709e8dcc47e7e5dc98ba6afc82c9b1373783d42` |
| `VERSION.json` | tools 0.3.12, source commit as above, 0 dirty files, Maven dependency 0.4.0 |
| Bundled host APK | sha256 `c23e84e4fccb368e64d49464146bceb8f25d46da307a45efea2c4320b29f72cb`, no `assets/portal_ed25519.pub` |
| Build | run 39564359560 (dispatched from `release/portal-tools-0.3.12`), retained artifact `12329584134` (`keliver-portal-tools-0.3.12-8139a55b…`, artifact digest `sha256:03762f16…`, expires 2026-11-20) |
| Device job | run 39567056811 (dispatched from `release/portal-tools-0.3.12`, pinned to the build's APK `c23e84e4…`): 28/0 and 19/0, on GitHub's emulator |

The build job's checks, on the packaged bundle (Linux): the refusal check
134/0, store home 32/0, resolver failure 73/0, adopter acceptance 25/0, the
identity gate (U21) 6/0, store identity 11/0, store recovery through
the packaged commands 144/0, device-host packaging hygiene (U22) 10/0, guest
bundle signing 4/0, `keliver-publish` 41/0, private key permissions (U27)
63/0, and the production-host (68/0, 62/0), iOS host (65/0, 65/0) and publish
(51/0, 33/0) scaffolders from `scripts/` and from the package. The pull
request's CI at `8139a55`: `ci.yml` 39564363865 (full graph, including the
generic editor's compile), reference-app 39564363859 and ios-host 39564363853,
all green.

Step 3 at the source commit: a `portal-tools-v0.3.12` tag runs only
`portal-tools.yml`, which has no write permission; `publish.yml` takes `v*`
only; the other workflows run on pull requests, `main`, a schedule or by hand.
No workflow was disabled, so step 5 restored nothing.

**Local checks on the attached bytes** (the retained artifact above, on the
releasing machine, macOS):

- `keliver-adopter-acceptance.sh`: 25/0;
- `keliver-acceptance-identity-check.sh`: 6/0;
- the compiled component preview, end to end, on an app made by this zip's
  `keliver-init` (Maven Central 0.4.0) with an opaque component of the same
  name and signature as one of Keliver's sample components:
  `keliver-portal --no-editor-build` (the generic editor) draws the
  placeholder; `keliver-portal` with an editor from `keliver-new-editor.sh`
  (components directory passed), the `compiledComponents` block and the
  scaffold's override line, and no adapters file beforehand, writes the
  adapters, builds the editor, and the canvas draws the app's compiled
  component. Checked by screenshots of the canvas (headless Chrome).

**Found and fixed during review** (the first candidate, `964982ef1`, was not
released): its generic editor drew Keliver's sample components in place of
an app's opaque components with the same names (the compiled adapters are
looked up by name; reproduced on that candidate's CI artifact, run
39562133883), and `keliver-portal` never wrote an app's adapters, so the
documented route for a per-app editor failed to compile. Both are fixed in
`2150b4fa6`; `8139a55` adds a CI compile of the generic editor and the
scaffold's wording.

**Not run for this release:** `keliver-device-verify.sh` on a physical phone.
The production refusals and the development route were checked on GitHub's
emulator only (the device job above).

## Known follow-ups

- Switching an app from the bundled editor to its own on the same port can
  keep running the cached generic editor until a hard reload: the editor is
  served by `python3 -m http.server` without cache headers (KNOWN_BUGS U38).
- Adding the components directory to an existing editor's `kotlin.srcDir` by
  hand also needs the keliver compose dependencies the scaffolder adds when it
  is given source directories; passing the directory to
  `keliver-new-editor.sh` is the supported route.
