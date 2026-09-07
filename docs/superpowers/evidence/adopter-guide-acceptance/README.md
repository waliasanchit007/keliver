# Adopter guide — workflow acceptance

**A workflow acceptance check.** Not an M4 comparison, and not evidence that
semantic access outperforms editing source. It establishes one thing: that the
documented adopter workflow runs, from a package, on an app that has never seen
this repository.

Reproduce with
`scripts/keliver-adopter-acceptance.sh <disposable-root> <package.zip> --serial <device>`.

## The package under test

```
keliver-portal-tools-0.3.3-local.zip
sha256 210a7abb4106914833745f97c152a8cce2b695c8c45facf384236121fe42e8a0
```

**Locally built candidate.** It is not published, and it is not what
`dev.keliver:*:0.3.3` on Maven Central gives you — the guide, the port fix and
the store contract in it exist only in this working tree.

## What was executed — 20/20

Only the package and the guide's stated prerequisites were used. No script from
this checkout, no undocumented configuration.

| step | result |
|---|---|
| `keliver-init MyApp` | scaffolded |
| `keliver-portal .` | server + editor + MCP command printed |
| `get_guide` | the **adopter** guide, 9 978 bytes, naming no repo-only command |
| `get_document {"screen":"home"}` | version 1, title `MyApp`, 7 handles |
| `apply_ops` dry run | `{"ok":true,"version":1}` — nothing written |
| `apply_ops` commit | `{"ok":true,"version":2}` |
| source diff | one line in `screens/home.kt` ([`screen-edit.diff`](screen-edit.diff)) |
| `./gradlew compileKotlinJs` | BUILD SUCCESSFUL |
| `keliver-new-device-target.sh` | device target added |
| `keliver-install-device-host.sh --serial …` | host installed |
| `serveDevelopmentZipline` + host restart | **`My Inbox` on the device** |
| `keliver-portal stop .` then start | restarted |
| after restart | edit present in **both** the document and the source |

## Ownership

`git status` alone cannot show this — the store lives outside the app and
generated files can be untracked — so the check compares **source fingerprints**
before and after:

```
+ src/jsMain/kotlin/device/Main.kt          ← keliver-new-device-target.sh (documented)
~ src/jsMain/kotlin/screens/home.kt         ← the semantic edit (intended)
+ src/jsMain/kotlin/screens/Compiled_home.kt ← generated version stamp (documented)
  src/jsMain/kotlin/logic/HomePresenter.kt  ← BYTE-IDENTICAL
```

Every change is accounted for and hand-owned logic was not touched. The
document store resolved outside the app (`…/apps/myapp-bf3f12da`), and the
developer's real global store was byte-identical afterwards.

## Defects found and fixed on this route

1. **`keliver-portal stop` then start failed** with `port 8077 is already in
   use` when nothing was listening. The port check was `lsof -ti ":$p"`, which
   matches client sockets in `TIME_WAIT` — a health check leaves those behind.
   Now `-sTCP:LISTEN`. Regression: the stop/start cycle in
   `keliver-adopter-tree-check.sh` (skipped unless run against an unpacked
   bundle, since `keliver-portal` requires one).
2. **The bundled guide was the contributor guide**, telling adopters to run
   Keliver's own dev script and edit `portal-app-lib/`. The package now ships
   `PORTAL_ADOPTER_GUIDE.md`; `GuideTest` fails if the bundled text names a
   repo-only path or drops the preview-versus-runtime warning.

## Guide corrections made from what was observed

* JDK: **17 or later** (17 and 21 both verified) — the first draft demanded 17.
* `Compiled_<screen>.kt` appears after the first edit; portal-owned, commit it.
* The document version restarts from the freshly ingested `.kt` after a portal
  restart, so `baseVersion` must come from the `get_document` you just made.
* `keliver-new-device-target.sh` also edits `build.gradle` and
  `settings.gradle`.
* The **editor port is fixed at 8096**, so two apps cannot run portals at once
  even on different server ports. Their stores stay separate; it is only a port
  collision.

## Limits

macOS only, one emulator, one app. The device route is emulator-specific
(`10.0.2.2`); a physical device needs a reachable host URL and was not
exercised. The preview-versus-runtime distinction is documented but the
per-app editor (`keliver-new-editor.sh`) was **not** built or run here.
