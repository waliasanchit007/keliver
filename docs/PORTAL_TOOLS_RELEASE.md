# Releasing keliver-portal-tools

**Publish the artifact that was verified. Never a rebuild.**

The tools line and the library line are independent. `portal-tools-vX.Y.Z` fires
`portal-tools.yml` only; it does not match the `v*` pattern `publish.yml`
listens on, so a tools release cannot publish a library. Bumping one to name the
other is wrong.

## Why the asset is uploaded by hand

A tools bundle is built by CI, then verified by CI: the adopter acceptance, the
foreign-relay identity regression, the packaging hygiene check, and — for the
device host — an emulator run that installs the APK and drives it.

All of that verifies **one specific set of bytes**. The build is not
byte-reproducible: the same commit built on macOS and on Linux produced two
different zips (see `RELEASE_REVIEW.md`). So a workflow that rebuilds at the tag
and attaches *its* output would publish bytes nobody has tested, while the
release notes cite verification runs that never saw them.

Therefore `portal-tools.yml` **builds and verifies candidates; it does not
publish.** There is no `release` job. Attaching the asset is a deliberate human
step against a named, retained artifact.

## Procedure

Everything below was executed for 0.3.4 on 2026-09-12 and is written from that
run.

### 0. Update the bundled guide's download block

`docs/PORTAL_ADOPTER_GUIDE.md` is **not just a doc** — `portal-mcp/build.gradle`
copies it into the MCP jar's resources as `PORTAL_USAGE.md`, and that jar ships
in the bundle. Its install block names a concrete version, so it describes the
*previous* release until you update it.

Bump the version in that block to the release you are about to cut, before the
commit you tag. Do **not** put the zip's own sha256 there: the guide ships
inside the zip it would be describing, so the claim falsifies itself on any
rebuild. The published `.sha256` sidecar is what adopters check against.

### 1. Build and verify a candidate

```bash
gh workflow run portal-tools.yml --ref <branch> -f ref=<commit>
```

Note the run id and the retained artifact it produces (30-day retention), then
verify that artifact on a device:

```bash
gh workflow run portal-tools.yml --ref <branch> \
  -f ref=<commit> \
  -f device_run_id=<build run id> \
  -f device_apk_sha256=<apk sha256 from the build run>
```

Both must be green. The device job refuses to run against an APK whose sha256
does not match, or one carrying an embedded portal key.

Note what `--ref` and `-f ref=` each bind. The **artifact under test** is pinned
by `device_run_id` + `device_apk_sha256`, which is the load-bearing gate. The
**verification script** comes from `--ref`, the dispatch branch — deliberately,
because a candidate commit can predate the verifier (`ec10e191a` has no
`keliver-device-verify.sh`). `-f ref=` is consumed by the build job only; the
device job ignores it. So the verifier and the verified can be different
commits, by design. If that matters for a given release, say which branch the
verifier came from in the release record.

### 2. Verify the retained artifact locally

```bash
gh run download <build run id> -D dl
shasum -a 256 dl/*/keliver-portal-tools-<v>.zip          # must equal the build run's
unzip -p <zip> '*/VERSION.json'                          # toolsVersion, sourceCommit, mavenDependencyVersion
unzip -p <zip> '*/host/*.apk' > host.apk
shasum -a 256 host.apk                                   # must equal the device run's
unzip -l host.apk | grep assets/portal_ed25519.pub       # must find NOTHING
```

`VERSION.json`'s `sourceCommit` is the commit to tag.

### 3. Check what a tag push would trigger — at the tagged commit

A tag push runs workflows **as they are defined at the tagged commit**, not as
they are on `main`. An old commit can carry an old, more permissive workflow.

```bash
# EVERY workflow at that commit, not just portal-tools.yml.
for f in $(git ls-tree --name-only <commit> .github/workflows/); do
  echo "== $f"
  git show "<commit>:$f" | grep -nE \
    'contents: *write|permissions: *write-all|packages: *write|softprops|ncipollo|create-release|upload-release|gh release|uploads\.github|api\.github'
done
```

Grepping only `portal-tools.yml` for `contents: write` and `softprops` is not
enough. It misses `permissions: write-all` (a real form that grants
`contents: write` without containing that string), a job-level rather than
workflow-level permission, `ncipollo/release-action`,
`actions/upload-release-asset`, a bare `gh release upload` inside a `run:`
block, a `curl` to `uploads.github.com` — and any *other* workflow file present
at that commit.

For 0.3.4 this mattered: at `ec10e191a` the workflow still had workflow-level
`contents: write` and an upload step gated only on `if: github.event_name ==
'push'`. It would have replaced the asset with its own rebuild.

If a tagged commit carries a publishing workflow, disable **only** that
workflow for the duration, recording its prior state, and confirm nothing is
queued:

```bash
gh workflow list --all --json path,state    # record this
gh run list --limit 20 --json status        # nothing in flight
gh workflow disable <workflow id>
```

Confirm the other tag-sensitive workflows cannot fire: `publish.yml` is `v*`
(a `portal-tools-v*` tag does not match), `ci.yml` has `tags-ignore: ['**']`,
and `publish-maven-central.yml` is `workflow_dispatch` only.

### 4. Tag, draft, upload, verify, publish

```bash
git tag -a portal-tools-vX.Y.Z <sourceCommit> -m "..."
git push origin portal-tools-vX.Y.Z          # must trigger nothing
gh run list --limit 3                        # confirm it triggered nothing

shasum -a 256 <zip> | sed 's|  .*/|  |' > <zip>.sha256
gh release create portal-tools-vX.Y.Z --draft --verify-tag \
  --title "keliver-portal-tools X.Y.Z" --notes-file NOTES.md \
  <zip> <zip>.sha256
```

**Download the draft asset back and hash it before publishing** — this is the
step that proves what is actually stored, not what was sent:

```bash
gh api -H "Accept: application/octet-stream" \
  repos/<owner>/<repo>/releases/assets/<asset id> > check.zip
shasum -a 256 check.zip
```

Then publish, and verify the *public* URL:

```bash
gh release edit portal-tools-vX.Y.Z --draft=false --latest=false
curl -sSL -o public.zip https://github.com/<owner>/<repo>/releases/download/portal-tools-vX.Y.Z/<zip>
shasum -a 256 public.zip
```

`--latest=false` keeps GitHub's "Latest" badge on the library release; a tools
release is not the project's latest release.

### 5. Restore

Re-enable any workflow disabled in step 3 and confirm the state matches what was
recorded. Re-enabling does not replay past events, and the tag push has already
happened, so nothing can retroactively replace the asset.

```bash
gh workflow enable <workflow id>
gh workflow list --all --json path,state     # compare against step 3
```

**What this does not cover.** Re-enabling replays nothing and the tag push has
already happened, so no *past* event can come back. But the protection is the
tag staying put, not the workflow at the head:

* `portal-tools-v0.3.4` points at `ec10e191a`, whose `portal-tools.yml` still
  has workflow-level `contents: write` and `softprops/action-gh-release`.
  Deleting and re-pushing that tag — or putting any new tag on that commit or
  an earlier one — runs **that** workflow, rebuilds on Linux, and replaces the
  verified asset with untested bytes.
* The published asset is not immutable (`gh release view --json ...` reports
  `isImmutable: false`), so it can be replaced by anything with `contents:
  write`.

Removing the `release` job at the head cannot change either fact. **Never move
or recreate a released tools tag.** If a release must be re-cut, use a new
version number.

## What a released tag guarantees

The tag names the commit in `VERSION.json`. It does **not** guarantee the asset
is what that commit builds today — it guarantees the asset is the artifact that
was verified. Re-verify any artifact before trusting it:

```bash
scripts/keliver-adopter-acceptance.sh <parent> <zip>
scripts/keliver-acceptance-identity-check.sh <parent> <zip>
scripts/keliver-device-verify.sh <zip> <work> <evidence>   # needs a device
```

These three live in this repository and are **not** shipped in the bundle
(`bin/` carries the scaffolder and launcher scripts only), so re-verification
needs a keliver checkout. Someone holding only the released zip can still check
its `.sha256`, read `VERSION.json`, and confirm the APK carries no
`assets/portal_ed25519.pub`.
