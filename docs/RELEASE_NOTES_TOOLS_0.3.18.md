# keliver-portal-tools 0.3.18 — release notes

**Status: CANDIDATE.** Not released. The verification table below is filled in as each step of
`PORTAL_TOOLS_RELEASE.md` is done.

What is in it since 0.3.17 (`7ea8940c3`):
- #205 (B4a: the bridge gate);
- #210 (B5a: hosts that serve an app's bridges);
- the tools bundle's `host/README.md` (from `DEVICE_HOST.md`) describes both, the iOS tap
  driver the reference app's CI uses (#197, #199), and the leak bound of the reference app's CI
  (#214).

#195–#204, #206–#209 and #211–#217 move the reference app and its CI (the S17–S17d device
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
  very version, with the same shape. Otherwise it skips the entry and takes the newest one it
  can run.
- A host checks the manifest's signed value again at load, on the network and on a cache start,
  and refuses a bundle it cannot serve (`bridge refused: …`), even when the index entry says
  nothing. With a report URL it reports `bridge-missing` or `bridge-shape`, and falls back as
  for any refused bundle.
- The relay's `/report` accepts those reasons, and `screen-failed` and `exception`, which hosts
  already sent.
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
name, as the reference app's CI does with a stub (S17c, S17d).

`DEVICE_HOST.md` ("Serving your app's bridges"); `BRIDGE_CODEGEN_DESIGN.md`, section "B5".

## Verification

| what | identity |
|---|---|
| source commit | (the candidate commit on this release's PR) |
| zip | |
| `.sha256` file | |
| `VERSION.json` | |
| device host APK | |
| candidate build | |
| packaged checks in that build | |
| device job | |
| checked locally | |
| tag push | |
| stored assets | |
| draft download-back | |
| public download | |

Run before this release, on `main`'s templates (the same host code as the candidate):
- the reference app's pull-request runs of #217: the CI emulator (reference-app `41579853553`)
  and simulator (ios-host `41579853306`) ran S2's binding check and S17–S17d, all PASS, with
  hosts scaffolded by this checkout and built against the published libraries.

Not verified for this release:
- a real generated bridge bound by a host, and a guest calling it on a device: the generated
  code is not published (B5c), and the first round trip runs on the JVM only (#211);
- the bridge gate on a phone: only on the CI emulator and simulator.
