# keliver-portal-tools 0.3.18 — release notes

**Status: RELEASED** 2026-11-03 (10:38:06Z), by `PORTAL_TOOLS_RELEASE.md`, under the
owner's 2026-10-27 handover. Tag `portal-tools-v0.3.18` (annotated, `154c03871`) →
`878311ae9a42611aa5fbd49c20c2d5436fe16d49`. Not marked "Latest" (`--latest=false`).

What is in it since 0.3.17 (`7ea8940c3`):
- #205 (B4a: the bridge gate);
- #210 (B5a: hosts that serve an app's bridges);
- the tools bundle's `host/README.md` (from `DEVICE_HOST.md`) describes both, the iOS tap
  driver the reference app's CI uses (#197–#199), and the leak bound of the reference app's CI
  (#214).

#194 records the 0.3.17 release. #195, #196, #200–#204, #206, #208, #209 and #211–#217 move the reference app and its CI (the S17–S17d device
scenarios run B4 and B5 on the CI emulator and simulator), add the bridge libraries
(`keliver-bridge`, its annotations and the KSP processor, B3; not published), and keep the
records.

**The libraries stay 0.4.1.** Nothing published to Maven Central changed since 0.4.1. Every
change here is in the scaffolders' templates, the relay (inside `keliver-publish` and
`keliver-portal`) and the docs. New apps get `dev.keliver:*:0.4.1`, as with 0.3.17. **A host
scaffolded earlier gets none of it until it is scaffolded again.**

## New: the bridge gate (B4, #205)

A bundle can declare the app bridges it calls, in its **signed** manifest metadata, which the
app's build writes:

```
zipline { metadata.put('keliver.bridges', 'ShopActions@2#cd5829702ffe49be') }
```

Each item is a bridge's name, the oldest version the bundle can talk to and, optionally, the
contract shape it was built against (a generated bridge's `NAME`, `VERSION` and `SHAPE`).

- `keliver-publish` (and the relay's own publish) copies it into the version's index entry as
  `constraints.bridges` (`{"ShopActions": {"min": 2, "shape": "…"}}`). It is never typed into
  the index. A malformed value is refused at publish, and nothing is stored.
- A host takes an entry only when it serves each bridge at that version or later, and, at that
  very version, with the same shape (compared when both the bundle and the host name one).
  Otherwise it skips the entry and takes the newest one it
  can run.
- A host checks the manifest's signed value again at load, on the network and on a cache start,
  and refuses a bundle it cannot serve (`bridge refused: …`), even when the index entry says
  nothing. With a report URL it reports `bridge-missing` or `bridge-shape`, and falls back as
  for any refused bundle.
- The relay's `/report` accepts those reasons and B5a's `bridge-bind`, and also the outcome
  `screen-failed` and the reason `exception`, which hosts already sent.
- The relay's legacy `/bundles/latest` (hosts from tools 0.3.7 or earlier) never answers with a
  bundle that names a bridge.

A host scaffolded earlier skips every entry needing a bridge (it does not know the constraint).

`DEVICE_HOST.md` ("bridges"); `BRIDGE_CODEGEN_DESIGN.md`, section "B4".

## New: hosts that serve an app's bridges (B5a, #210)

A host serves the bridges the app declares when it configures the host, so the gate knows
before the first lookup which bundles it can run:

- Android: `KeliverConfig(bridges = …)` or `KeliverConfig.fromBuild(context, …, bridges = …)`.
- iOS: `Keliver.shared.bridges`, set from Swift before the host starts.

Each is a `KeliverBridgeBinding` (`Bridges.kt`, in both hosts): a generated bridge's `NAME`,
`VERSION` and `SHAPE`, and a `bind` that calls the generated `bind` with a
`KeliverBridgeScope` (this load's Zipline, the main thread's context, and a log that writes in
debug builds only).

- A list the host must not serve is refused when it is set: a name a bundle could not name, a
  version outside 1..999999999, a shape that is not 16 lowercase hex digits, a name twice,
  `HostSqlDriver`, `HostHttp`, or a name starting with `Keliver` (the host's own services).
- Each code load binds the app's bridges first, on Zipline's thread, then the host's own
  services. A binding must bind exactly its declared name, once (the host sees what it bound
  through Zipline's event listener). One that binds anything else, or throws, fails the load as
  `bridge bind failed: …` (reason `bridge-bind`), the last good bundle too, until a fixed build
  is installed.
- An app that declares none serves none: its host binds what it bound before.

The generated bridge code (`keliver-bridge*`) is **not published yet**: it reaches apps with the
next libraries release. Until then, a binding can bind any `ZiplineService` under the declared
name, as the reference app's CI does with a stub: S2 checks that a load ran its binding,
and S17c and S17d gate on its shape.

`DEVICE_HOST.md` ("Serving your app's bridges"); `BRIDGE_CODEGEN_DESIGN.md`, section "B5".

## Verification

| what | identity |
|---|---|
| source commit | `878311ae9a42611aa5fbd49c20c2d5436fe16d49` (the candidate commit on PR #218; `VERSION.json`'s `sourceCommit`, `sourceDirtyFiles` 0). A first candidate, `ac595864f`, was replaced before its build finished (run `41597988720`, cancelled): the fact-check found `DEVICE_HOST.md`, shipped as `host/README.md`, still calling B5 "next" |
| zip | `keliver-portal-tools-0.3.18.zip`, sha256 `f85960e47d68f22974a281404c02375f4337d4643ee5525b7d5738d2d9757992`, 92,150,799 bytes |
| `.sha256` file | 98 bytes, sha256 `bd3c2d8e729808f231ffede2079480d55db5a8335ccd69027c0e23c84becfd4a` |
| `VERSION.json` | toolsVersion 0.3.18, mavenDependencyVersion 0.4.1 |
| device host APK | `host/keliver-device-host-0.3.18.apk`, sha256 `4cbf08702120af7634c779fac1109a9aef8d87f3029117548772e49c90fb1bb7`; no `assets/portal_ed25519.pub` |
| candidate build | `portal-tools.yml` run `41598629891` (bundle job `125791828208`) at `878311ae9`; retained artifact `13214467311` (artifact zip `88646157…`) |
| packaged checks in that build | adopter acceptance 25/0 (with the U21 identity gate), foreign-relay refusal 6/0, store identity 11/0, device-host hygiene 10/0, store recovery 144/0, production-host scaffolder 86/0 (from `scripts/`) and 76/0 (from the package), iOS host 77/0 and 77/0, publish target 51/0 and 33/0, screen and component scaffolders 9/0, `keliver-run-android` 22/0, the embed floor 11/0, `keliver-publish` 41/0, key permissions 63/0; the bash-3.2, store-resolver and caller-refusal checks 134/0, 32/0 and 73/0; guest bundle signing 4/0 |
| device job | `portal-tools.yml` run `41619915837` (job `125862734408`), dispatched from `release/tools-0.3.18` at `878311ae9` (the candidate itself) against the artifact above, APK sha256 pinned: the device verifier 19/0 and its packaged acceptance 28/0, on the CI emulator |
| checked locally | the artifact downloaded through `gh api …/actions/artifacts/13214467311/zip` (`88646157…`): both sha256s, `VERSION.json`, no key in the APK, both hosts' `Bridges.kt` in `templates/`, and every template file equal to `878311ae9`'s (the zip adds only the generated `production-host-embed/dependency-floor.json`); `host/README.md` says "from tools 0.3.18" and "(B5, below)" |
| tag push | ran only `portal-tools.yml`'s read-only rebuild (`41620684522`), never attached; step 3 found no workflow at `878311ae9` that can write a release (`portal-tools.yml` is `contents: read`; `publish.yml` listens on `v*`; `ci.yml` ignores tags; the listed `release.yaml` is not in that tree), so nothing was disabled |
| stored assets | release `432558117`: the zip (asset `705221688`) and the `.sha256` (asset `705221684`), server digests and sizes equal to the files above |
| draft download-back | worked: sha256 `f85960e4…`, 92,150,799 bytes |
| public download | worked from the releasing machine after publishing: the zip `f85960e4…`, 92,150,799 bytes, and the public `.sha256` names the same hash |

Run before this release, on `main`'s templates (the same host code as the candidate):
- the reference app's pull-request runs of #217: the CI emulator (reference-app `41579853553`)
  and simulator (ios-host `41579853306`) ran S2's binding check and S17–S17d, all PASS, with
  hosts scaffolded by this checkout and built against the published libraries the reference
  app pins (0.3.3, not 0.4.1).

Not verified for this release:
- a real generated bridge bound by a host, and a guest calling it on a device: the generated
  code is not published (B5c), and the first round trip runs on the JVM only (#211);
- the bridge gate on a phone: only on the CI emulator and simulator.
