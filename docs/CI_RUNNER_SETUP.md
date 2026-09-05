# Self-hosted GitHub Actions runner setup

By default Keliver's CI runs on GitHub-provided `macos-latest` runners.
macOS minutes count against the GitHub Actions quota at a 10× multiplier
on private repos, which the project hit during heavy adopter onboarding.

The two CI workflows (`.github/workflows/ci.yml` and
`.github/workflows/publish.yml`) honor an optional repo variable
`CI_RUNNER`. Setting it to `self-hosted` routes all runs to a runner
you install on a Mac you control, bypassing GHA billing entirely.

This document walks through the one-time setup. ~15 minutes.

## Why self-hosted, not Linux

The Keliver build requires macOS to compile iOS targets (the `iosArm64`
and `iosSimulatorArm64` klibs that ship in the published artifacts).
Self-hosted Linux runners would fail at `compileKotlinIosSimulatorArm64`.

`act` (Docker-based local GHA emulation) doesn't help here either — it
runs Linux containers with no working iOS toolchain.

## Prerequisites on the runner machine

- macOS (any current version)
- Apple Silicon recommended (`darwin-arm64`); Intel Macs work too
- Xcode 16+ installed (the same prerequisite the existing `macos-latest`
  CI assumes)
- JDK 21 — `actions/setup-java@v4` will download this automatically if
  missing, so manual install isn't required
- A persistent internet connection so the runner can poll GitHub for jobs
- **The Android SDK, with `ANDROID_HOME` exported to the runner.**
  GitHub-hosted macOS images set this for you; a self-hosted machine does
  not, and the build dies at the first Android-aware task with
  `SDK location not found`. Put it in `~/actions-runner/.env` (see the
  proxy section below for the file's other uses) rather than your shell
  profile — a LaunchAgent does not read `.zshrc`:
  ```
  ANDROID_HOME=/Users/<you>/Library/Android/sdk
  ANDROID_SDK_ROOT=/Users/<you>/Library/Android/sdk
  ```
  The platform the build compiles against is `compileSdkVersion(35)` in
  `build-support/.../RedwoodBuildPlugin.kt`. Having only newer platforms
  installed is not enough — check `ls $ANDROID_HOME/platforms`.
- **Real disk headroom.** See "What can go wrong" below; a full-graph
  build needs far more than a few GB.

## Step 1 — Register the runner in the repo

1. Open `https://github.com/waliasanchit007/keliver/settings/actions/runners`
2. Click **New self-hosted runner** → **macOS** → pick architecture
3. GitHub displays a one-time registration token + the install commands.
   They look roughly like:

   ```bash
   mkdir -p ~/actions-runner && cd ~/actions-runner
   curl -O -L https://github.com/actions/runner/releases/download/v2.319.0/actions-runner-osx-arm64-2.319.0.tar.gz
   tar xzf actions-runner-osx-arm64-2.319.0.tar.gz
   ./config.sh --url https://github.com/waliasanchit007/keliver --token <TOKEN_FROM_GITHUB>
   ```

4. During `config.sh` it asks a few questions:
   - **Runner group:** Default
   - **Runner name:** anything you'll recognize (e.g., `keliver-mac-mini`)
   - **Labels:** leave empty (the workflow doesn't filter on labels yet)
   - **Work folder:** Default (`_work`)

The token is one-time and short-lived. Generate a fresh one if you wait
too long.

### Or do the whole thing from the CLI

Faster, and it's what was used to rebuild this runner on 2026-09-05:

```bash
mkdir -p ~/actions-runner && cd ~/actions-runner
V=2.337.0
curl -fL -o runner.tgz \
  "https://github.com/actions/runner/releases/download/v${V}/actions-runner-osx-arm64-${V}.tar.gz"
shasum -a 256 runner.tgz    # compare against the SHA in the release notes
tar xzf runner.tgz && rm runner.tgz

TOKEN=$(gh api -X POST repos/waliasanchit007/keliver/actions/runners/registration-token --jq .token)
./config.sh --url https://github.com/waliasanchit007/keliver --token "$TOKEN" \
            --name keliver-mac --work _work --unattended --replace
```

`--replace` takes over an existing registration with the same name,
which is what you want after a machine dies — the old entry lingers as
`offline` and would otherwise collide. `--unattended` skips the prompts.
No labels are needed: `self-hosted` is applied automatically and that is
exactly what `CI_RUNNER` matches.

The release-asset CDN returns intermittent `502`s; just retry, and check
the downloaded size against the release page (~128 MB) rather than
trusting a `200`. A truncated download shows up as
`tar: Unrecognized archive format`.

## Step 2 — Run the runner as a background service

```bash
cd ~/actions-runner
./svc.sh install
./svc.sh start
# To check status / stop later:
./svc.sh status
./svc.sh stop
```

`svc.sh install` registers a `com.github.actions.runner.<repo>.<name>.plist`
LaunchAgent that auto-starts the runner on login. Verify it's picking up
jobs by triggering any workflow (e.g., re-run an existing failed CI run)
and watching the runner's terminal output.

## Step 3 — Flip the workflow to use it

In the keliver repo: **Settings → Secrets and variables → Actions →
Variables → New repository variable**:

- **Name:** `CI_RUNNER`
- **Value:** `self-hosted`

Both `ci.yml` and `publish.yml` already read this variable
(`runs-on: ${{ vars.CI_RUNNER || 'macos-latest' }}`). The change is
instant — the next workflow trigger picks up the new runner. No
workflow YAML edit needed.

To revert (e.g., your Mac is down and you want to fall back to the
cloud): delete the `CI_RUNNER` variable or set it to `macos-latest`.

## Step 4 — (Optional) Cache Gradle dependencies

The `gradle/actions/setup-gradle@v4` action caches Gradle's local
metadata into the GHA artifact cache by default — that works on
self-hosted runners too, no change needed. The cache lives on GitHub's
side (within the GHA quota for cache storage, which is a separate,
larger free tier than runner minutes).

For even faster builds, point Gradle at a local mirror:

```bash
# ~/.gradle/gradle.properties on the runner machine
org.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.parallel=true
```

## What can go wrong

- **Runner offline / unresponsive.** Workflows queue waiting for the
  runner to come back. If your Mac sleeps, the runner pauses. Disable
  sleep when on AC power, or set the LaunchAgent to wake the machine
  via `pmset` (out of scope here).
- **Disk filling up.** The runner's `_work/` directory holds
  per-workflow workspaces. Periodically clear `~/actions-runner/_work/`
  if you're running short on disk. A full-graph Keliver build (JVM +
  Android + JS + iOS) needs well more than a few GB. On 2026-09-05 the
  runner machine was at 99% with 4.3 GB free; the reliable reclaim is
  Xcode's regenerable caches, not `_work/`:
  ```bash
  du -sh ~/Library/Developer/Xcode/*        # find the big ones
  rm -rf ~/Library/Developer/Xcode/DerivedData/*        # pure build output
  rm -rf ~/Library/Developer/Xcode/"iOS DeviceSupport"/* # re-downloaded per device
  ```
  That recovered 81 GB. Do **not** clear `~/Library/Developer/Xcode/Archives`
  — it holds dSYMs for shipped builds.
- **GitHub token rotated / runner unregistered.** If the runner shows
  as "offline" in the GitHub UI for >24h, GitHub auto-removes it. Just
  re-run Step 1.
- **Concurrency.** Multiple PR runs serialize on a single self-hosted
  runner (there's only one machine). For the current 1-developer
  workload this is fine; if you ever have parallel contributors,
  consider adding a second runner or falling back to cloud.

## Security note

**The repo is public and `CI_RUNNER=self-hosted` is set.** That means
`ci.yml`, which triggers on `pull_request`, routes fork PRs to a Mac you
own — a PR author can execute arbitrary code on it. This section
previously said "Keliver is a private repo with a single trusted
maintainer, so running PRs on a self-hosted runner is safe," and told
the reader to revisit before going public. The repo went public and this
was not revisited; treat that as the lesson, not just the setting.

Mitigation in place since 2026-09-05: the fork-PR approval policy is set
to **`all_external_contributors`**, so no outside PR runs without an
explicit maintainer approval.

```bash
# check
gh api repos/waliasanchit007/keliver/actions/permissions/fork-pr-contributor-approval
# set
gh api -X PUT repos/waliasanchit007/keliver/actions/permissions/fork-pr-contributor-approval \
  -f approval_policy=all_external_contributors
```

GitHub's default is `first_time_contributors`, which only gates
someone's *first* PR — after one merged contribution their fork PRs run
automatically. That default is not sufficient here.

Stronger alternatives if contributor volume grows:

- Hybrid — cloud for PRs, self-hosted for push-to-main. Removes the risk
  structurally instead of relying on a policy toggle:
  ```yaml
  runs-on: ${{ github.event_name == 'pull_request' && 'macos-latest' || vars.CI_RUNNER }}
  ```
- Move CI back to cloud `macos-latest` entirely and budget the macOS
  minutes.

Also worth knowing: the runner machine holds your SSH keys, `gh` token,
signing setup, and unrelated work repositories. The blast radius of a
malicious PR is the whole machine, not just the checkout.

## TLS-inspecting corporate proxies (Netskope / Zscaler)

If the runner machine sits behind a TLS-inspecting proxy, jobs fail
early with one of:

```
##[error]self-signed certificate in certificate chain
##[error]unable to get issuer certificate; if the root CA is installed locally, try running Node.js with --use-system-ca
```

`curl` and `git` keep working, which makes this confusing — they trust
the corporate CA through the macOS keychain. **Node and Java each ship
their own CA store and do not.** Every JS-based action (`checkout`,
`setup-java`, `setup-gradle`) is Node.

The two errors are different failures. The first means no corporate CA
is trusted at all. The second means you supplied a leaf/intermediate but
not the root that issued it — supply the **full chain**.

Fix by putting both in the runner's `.env`, which the runner injects
into every job, then restart the service:

```bash
# ~/actions-runner/.env
NODE_EXTRA_CA_CERTS=/path/to/full-ca-bundle.pem
JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/path/to/jssecacerts -Djavax.net.ssl.trustStorePassword=changeit
```

```bash
cd ~/actions-runner && ./svc.sh stop && ./svc.sh start
```

`NODE_EXTRA_CA_CERTS` *appends* to Node's built-in roots, so a bundle
that also contains the public roots is harmless. The Java setting is
needed separately because the JDK `setup-java` installs has a stock
`cacerts` with no corporate CA, so Gradle's dependency resolution
against Maven Central fails even after Node is fixed.

**A third place this bites, with a disguised error.** Any Kotlin/Wasm or
Kotlin/JS build shells out to `yarn` for its npm dependencies, and yarn
reports a TLS rejection as a *missing package*:

```
error Couldn't find package "@js-joda/core@3.2.0" required by "…" on the "npm" registry.
error Couldn't find package "format-util@^1.0.5" required by "…" on the "npm" registry.
```

Those packages exist. The real failure is `SELF_SIGNED_CERT_IN_CHAIN`,
and `NODE_EXTRA_CA_CERTS` fixes it. Confirm in one line:

```bash
node -e "require('https').get('https://registry.yarnpkg.com/format-util',r=>console.log(r.statusCode)).on('error',e=>console.log('ERR',e.code))"
# ERR SELF_SIGNED_CERT_IN_CHAIN   -> CA not trusted
# 200                             -> fine
```

This one is worth knowing about beyond CI: it hits **adopters** building
their own portal editor (`keliver-new-editor.sh` →
`wasmJsBrowserDistribution`) on any corporate network, and the error text
sends you hunting for a dependency problem that does not exist.

To confirm a bundle actually contains the whole chain:

```bash
openssl x509 -in <leaf>.pem -noout -subject -issuer   # note the issuer
# then check that issuer's subject also appears in the bundle
```
