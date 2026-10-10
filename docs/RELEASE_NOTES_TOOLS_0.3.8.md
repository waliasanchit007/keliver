# keliver-portal-tools 0.3.8 — release notes

**Status: CANDIDATE.** Nothing has been tagged or published. Tag and publish
only after the owner's explicit approval for 0.3.8. Approvals of 0.3.6 and
0.3.7 do not carry over. Tools 0.3.7 and earlier, and the Maven 0.3.3
libraries, are unchanged. The tools version and the library version are
separate lines: 0.3.8 still scaffolds against `dev.keliver:*:0.3.3`.

| | |
|---|---|
| source commit to tag | **`c0e71108c4821132a3f711137622a80bf8895a6d`** (`VERSION.json` `sourceCommit`, `sourceDirtyFiles: 0`; tools 0.3.8, Maven dependency 0.3.3) |
| zip | `keliver-portal-tools-0.3.8.zip`, 90,532,260 bytes; zip sha256 **`7ea7f11d9fdebce84861cf9224d324c15d00451ac175d84f3cd51b683c43bc14`** |
| bundled dev-host APK sha256 | **`afa9daddf033f6f4c49d9cbaab60c4206965c9c40c0f52b157034dd8228ef09d`**, no embedded portal key |
| build run | [`37932744136`](https://github.com/waliasanchit007/keliver/actions/runs/37932744136), retained artifact `11617444772` (90,524,155 bytes, `sha256:dd9abb95…`) |
| device run | [`37935499926`](https://github.com/waliasanchit007/keliver/actions/runs/37935499926): 19/0, 28/0 |

## New: publish without the relay — `bin/keliver-publish` (W3)

```bash
keliver-publish [app-dir] --out DIR [--public-key-file PATH] [--channel stable] [--skip-build] [--init]
```

- **What it does.** It builds the app's `publishTask`. It refuses a bundle that
  isn't signed with the app's key or is incomplete, and then writes nothing.
- **What it writes.** `DIR/bundles/v<N>/` (the compile output, unchanged) and
  `DIR/bundles/index.json` (format 1). Serve `DIR` from any static host or CDN
  over HTTPS.
- **The live index is the state.** Download the served `bundles/` first.
  Without an index it refuses, unless `--init` marks the first publish.
- **CI.** `KELIVER_SIGNING_KEY_FILE` names the private key for the signing
  block. Nothing prints it.

The production hosts these scaffolders write read `bundles/index.json`. They
fall back to the relay's `/bundles/latest` only on a 404.

## New: release controls (W4)

- **Signed sequence.** Every publish signs its sequence into the manifest
  (`metadata["keliver.sequence"]`).
- **Rollback floor.** Hosts keep the highest sequence they have run, per key,
  and refuse anything below it: on the network, from the cache, and on the
  cache start's network fallback.
- **Rollback.** `--republish <version>` publishes an older bundle's unchanged
  modules as a new sequence. The app's `keliverResign` task signs them, and
  nothing is compiled.
- **Channels.** Both host scaffolders take `--channel` (default `stable`). A
  host takes its own channel and stable. `--promote <sequence> --channel <name>`
  offers a published bundle on another channel, with no private key.
- **Staged rollouts and host-version gates.** `--rollout <percent>`,
  `--min-host-version` and `--max-host-version` go on a publish, republish or
  promotion. `--set-rollout <sequence> --rollout <percent>` raises or halts a
  rollout without a key. Hosts that already ran a bundle keep it.

Details: `docs/DEVICE_HOST.md` §2–3, the adopter guide's "Publish from CI to a
static server", and the tools README.

## Changed: the signing block (v2, with sequence and `keliverResign`)

`keliver-new-publish-target.sh` writes a block that:
- reads `KELIVER_SIGNING_KEY_FILE`;
- signs the publish sequence;
- registers `keliverResign`.

Apps wired by 0.3.6 or 0.3.7: **run `keliver-new-publish-target.sh` again.**
It replaces exactly either released block, changes nothing else, and refuses
an edited one. The U31 property is unchanged: the private key is never a task
input, a process argument or a log line.

## Changed: the candidate build checks the packaged `keliver-publish`

`portal-tools.yml` runs `scripts/keliver-publish-selftest.sh --package` against
the candidate zip's own `bin/keliver-publish` and `relay/`. The reference app's
harnesses (`ci/prepare.sh`, `ci/w3/ios-static.sh`) use the zip's
`bin/keliver-publish` and `bin/keliver-new-publish-target.sh` when the zip
ships them. In candidate mode a missing one is a failure, not a fallback.

## Upgrading from 0.3.7

- **Hosts.** Hosts scaffolded by 0.3.7 or earlier don't read
  `bundles/index.json` and have no rollback floor, channel or constraints. The
  templates are copied at scaffold time, so re-scaffold (or port the template
  changes) to use static publishing. Hosts from before W4.5 skip any index
  entry that carries `constraints`.
- **Signing block.** Upgrade it as above.

## Known issues

- **Device data.** A reinstall or "clear data" resets the host's rollback floor
  and install id, and device backups copy both to a new device.
- **The relay.** Its `/publish` has no republish, channels or rollouts; the
  relay is the development route.
- **Proof.** Nothing has run on physical devices yet (plan W7).
- **The next signing-block change** must first add this block to
  `templates/publish/legacy/` as `signing-0.3.8.gradle`, so apps wired by 0.3.8
  can upgrade.
- **Carried over.** The 0.3.7 notes' known issues that are not fixed above.

## Candidate verification (recorded after the build; not in the built commit)

### Candidate 1 — `c0e71108`

It is built from `release/portal-tools-0.3.8` (PR #94), stacked on W4 (#93).
The verifiers ran from the same branch, at `c0e71108` and then `a60624f0`
(see the iOS row).

| | |
|---|---|
| build run | [`37932744136`](https://github.com/waliasanchit007/keliver/actions/runs/37932744136): every portable check green. **New: the packaged `keliver-publish` self-test 40/0**, run against the candidate zip's own `bin/keliver-publish` and `relay/`: publish, the refusals, `--republish`, `--promote`, constraints and `--set-rollout` with no key. The publish target is 41/0 with `--build` (the real `keliverResign`) and 25/0 from the zip. Also: the production-host scaffolder 45/0 and 40/0, the iOS scaffolder 48/0 twice, and U27 key permissions 63/0/0 |
| device run | [`37935499926`](https://github.com/waliasanchit007/keliver/actions/runs/37935499926): the APK pinned by sha256 (`afa9dadd…`). The device checks are 19/0 and the packaged acceptance 28/0 (a signed v1 stored, an unsigned bundle refused and not stored, the production refusal cold and warm) |
| local check | The zip (`7ea7f11d…`) and APK (`afa9dadd…`) hashes match the build run. `VERSION.json` is as above. The APK has no `assets/portal_ed25519.pub`. `bin/` scripts are 755, and `relay/bin/keliver-publish-jvm` is present. These are byte-identical to the commit's: the bundle's `templates/publish/signing.gradle` and both `legacy/` blocks, both hosts' `BundleIndex.kt`, `bin/keliver-publish`, and the three scaffolders. So is the guide inside the MCP jar (`PORTAL_USAGE.md`). There are no `.priv`, `.pem`, `.jks` or keystore files. The packaged `keliver-publish` self-test was rerun here against the downloaded zip: 40/0 |
| iOS, the candidate zip itself | `reference/inventory/ci/ios.sh` with `KELIVER_CANDIDATE_SHA256=7ea7f11d…`, on this Mac (Xcode 26.4.1, iOS 26.4 simulator, iPhone 17 Pro; disposable simulator, Gradle home and Konan dir). The app was recreated from the candidate zip. The host was scaffolded by the zip's own `bin/keliver-new-ios-host.sh`. W3/W4 used the zip's own `bin/keliver-publish` and `bin/keliver-new-publish-target.sh` (the app already had the current block). **Run 1: 91/1.** The one FAIL was the harness, not the candidate: P2 expected the host to fall back to `/bundles/latest`, which only a 0.3.7-or-earlier relay forces, but the candidate's relay serves `bundles/index.json` and the host correctly looked v1 up through it. The harness was fixed in `a60624f0` (P2 now asks the relay which lookup applies). **Run 2, with that fix: 92/0**, including P1–P7 and S2–S15 (republish, channels and promotion, rollout 0/100/halt, the host-version gate, and the rollback floor on the network, the cache and the fallback). Evidence: `docs/superpowers/evidence/tools-0.3.8-candidate-ios/` (both runs' results; run 2's console logs and screen readings) |
| tag push | At `c0e71108`, only `portal-tools.yml` matches `portal-tools-v*`, and it is read-only (no job with `contents: write`). `publish.yml` (`packages: write`) fires on `v*` only. `pages.yml` (`id-token: write`) runs only on a push to `main`. `ci.yml` ignores tags. `ios-host`, `reference-app`, `compat-matrix` and `publish-maven-central` don't trigger on tags. Since 0.3.7 only `ios-host.yml`, `reference-app.yml` and `portal-tools.yml` changed; `portal-tools.yml` gained one read-only step |

**Recommendation:** tag `portal-tools-v0.3.8` on `c0e71108` and attach
`keliver-portal-tools-0.3.8.zip` (`7ea7f11d…`) from the retained artifact
`11617444772`, per `docs/PORTAL_TOOLS_RELEASE.md` step 4. **Only on the owner's
explicit approval of 0.3.8.**
