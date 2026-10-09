# keliver-portal-tools 0.3.8 — release notes

**Status: CANDIDATE.** Nothing has been tagged or published. Tag and publish
only after the owner's explicit approval for 0.3.8. Approvals of 0.3.6 and
0.3.7 do not carry over. Tools 0.3.7 and earlier, and the Maven 0.3.3
libraries, are unchanged. The tools version and the library version are
separate lines: 0.3.8 still scaffolds against `dev.keliver:*:0.3.3`.

The candidate's identities are recorded below once it is built and verified.

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

To be filled in:
- the build run and its retained artifact;
- the device run, with the APK pinned by sha256;
- the local check of the retained artifact;
- `ios.sh` on the candidate zip (P1–P7 and W3/W4 S2–S15, with the zip's own
  `bin/keliver-publish`);
- the step-3 workflow check at the source commit.
