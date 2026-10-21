# keliver-portal-tools 0.3.12 — release notes

**Status: CANDIDATE — not released.** Tag and publish only on the owner's
explicit go, following [`PORTAL_TOOLS_RELEASE.md`](PORTAL_TOOLS_RELEASE.md).

| | |
|---|---|
| tag | `portal-tools-v0.3.12` → *(the verified candidate's `VERSION.json` `sourceCommit`; not tagged)* |
| release | *(none yet)* |
| zip asset | `keliver-portal-tools-0.3.12.zip` *(the retained, verified CI artifact; not uploaded)* |
| `.sha256` asset | *(not uploaded)* |

What is in it since 0.3.11 (`ebbb24c96`): PRs #130 (relay tests use their
own store), #131 (K6), #132 (compiled component preview), #134 (K7), #135
(U37) and #136 (the 0.4.0 libraries). #127, #128, #129, #133 and #137 record
releases and evidence and change nothing in the bundle.

**New apps get the 0.4.0 libraries** (`dev.keliver:*:0.4.0`, on Maven Central
since 2026-10-21): `keliver-init`, `keliver-new-editor.sh`,
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

Source that made a component opaque or a call RawCode is now editable, and
writes back surgically (only the changed part; a no-op is byte-identical):

- **Equality and compound conditions, derived Boolean locals (K6):**
  `colorArgb = if (b.kind == "card" && b.open) C.Ink else C.Muted`,
  `if (b.tier == C.Top || b.flagged) { … }`, and in a component body
  `val active = enabled && !loading` read as `if (active)` / `if (!active)`.
  An equality is `field == <operand>` or `!=`, with the field on the left and
  a String, Int or Boolean literal or design token of the field's declared
  kind; terms join with `&&` only or `||` only. A derived local reads only
  parameters and is inlined where it is read. Mixed joins, parentheses,
  negated groups, field-vs-field and nullable or mismatched fields stay
  RawCode with a diagnostic. A condition test the field's declared type
  cannot take (`if (b.count)` with `count: Int`) is now refused as an edit and
  read as code; before, it could be written.
- **Int arithmetic and Ints as Dps (K7):** `cornerRadiusDp = size / 2`,
  `fontSize = b.size * 2 / 5`, `widthDp = b.columns * 48 + 8`,
  `Spacer(height = Dp(gap.toDouble()))` and `(b.gap + 4).dp`, where source
  takes an Int or a Dp. Operands are decimal Int literals, Int design tokens
  and Int bindings, parameters or item fields, under `+ - * / %` and
  parentheses, with Kotlin's precedence and 32-bit overflow; `/` and `%`
  divide only by a non-zero literal. A derived Int local (`val half = size / 2`)
  is inlined where it is read. Write-back keeps the author's parentheses and
  Dp spelling. Expressions of literals only, division by a non-literal, calls,
  Long/Double/Float/nullable operands and modifier arguments stay RawCode.
  `x.uppercase()` / `lowercase()` stay RawCode on purpose: a device runs
  Kotlin/JS in QuickJS, whose case tables a browser preview cannot vouch for.

The canvas evaluates both with the screen's mocks and an instance's
arguments, and the compiled-vs-interpreted parity of each form is pinned by
tests on both preview targets.

## New on the canvas: compiled component preview

An **opaque** project component (its body is outside the portal grammar) used
to preview as a placeholder. A per-app editor built on the 0.4.0 libraries
(`keliver-new-editor.sh`) can now draw it with the app's own compiled
composable: add a `compiledComponents` block to `keliver.portal.json`
(`out`, and optionally `packageName`, `valName`, `sameModule`) and
`override val compiledComponents = AppCompiledComponents` to the editor's
`AppPreviewEntry` (the scaffold shows both, commented). The relay in this
bundle then writes the adapters file before each preview build. Transparent
components keep their editable view; a compiled component that throws while
composing falls back to its placeholder. Without the block nothing changes.

This bundle's own editor has no app code, so an adopter's opaque components
stay placeholders there; it draws its built-in demo components this way. The
stand-alone generator (`:portal-relay:compiledComponentAdapters`) is a task in
the Keliver repository and is not shipped in the bundle.

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

*(to be filled from the verified CI build, as for 0.3.11)*

| | |
|---|---|
| Source commit | |
| Zip | `keliver-portal-tools-0.3.12.zip`, sha256 |
| `VERSION.json` | tools 0.3.12, Maven dependency 0.4.0 |
| Bundled host APK | sha256 , no `assets/portal_ed25519.pub` |
| Build | run , retained artifact |
| Device job | run |

Local pre-gates on a local build of the candidate commit (a local build is
not the bytes to attach; the build is not byte-reproducible):

- see the release branch's commit log and the preparation report.
