# Self-hosted GitHub Actions runner setup

By default Konduit's CI runs on GitHub-provided `macos-latest` runners.
macOS minutes count against the GitHub Actions quota at a 10× multiplier
on private repos, which the project hit during heavy adopter onboarding.

The two CI workflows (`.github/workflows/ci.yml` and
`.github/workflows/publish.yml`) honor an optional repo variable
`CI_RUNNER`. Setting it to `self-hosted` routes all runs to a runner
you install on a Mac you control, bypassing GHA billing entirely.

This document walks through the one-time setup. ~15 minutes.

## Why self-hosted, not Linux

The Konduit build requires macOS to compile iOS targets (the `iosArm64`
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
  if you're running short on disk.
- **GitHub token rotated / runner unregistered.** If the runner shows
  as "offline" in the GitHub UI for >24h, GitHub auto-removes it. Just
  re-run Step 1.
- **Concurrency.** Multiple PR runs serialize on a single self-hosted
  runner (there's only one machine). For the current 1-developer
  workload this is fine; if you ever have parallel contributors,
  consider adding a second runner or falling back to cloud.

## Security note

For now Konduit is a private repo with a single trusted maintainer, so
running PRs on a self-hosted runner is safe — there's no way for an
untrusted contributor to land code that runs on your Mac.

**Before flipping the repo to public** (Phase 6 of
`PUBLIC_LAUNCH_ROADMAP.md`), revisit this:

- Either restrict self-hosted runs to PRs from maintainer-approved
  forks only (GitHub Actions has settings for this), or
- Move CI back to cloud `macos-latest` and budget for the macOS minute
  cost, or
- Use a hybrid: self-hosted for push-to-main runs, cloud for PR runs.
