# Maven Central setup checklist (Phase 5)

Tracks the user-action steps to move Konduit from **GitHub Packages
(private, requires PAT)** to **Maven Central (public, no auth)**.
Phase 5 of [PUBLIC_LAUNCH_ROADMAP.md](../PUBLIC_LAUNCH_ROADMAP.md).

Once this is done, downstream consumers can drop
`implementation("io.github.waliasanchit007:konduit-host:1.0.0-...")`
into a Gradle build with `mavenCentral()` configured and just have it
work — no `gpr.user` / `gpr.token` step, no GitHub Personal Access Token.

**The in-repo build wiring is done** (vanniktech plugin, Central
coordinates with an overridable groupId, Konduit-correct POMs, gated
signing, and a manual `publish-maven-central.yml` workflow — see
Steps 4 / 4b). What remains is **three external steps only the
maintainer can do**: claim the namespace at Sonatype, generate a GPG
signing key, and add the four repo secrets. After that, releasing is
a single manual workflow run.

## Namespace decision

The project is publishing under the **`io.github.waliasanchit007`**
Sonatype namespace (GitHub-vanity flow — no domain required).
Artifacts go from `dev.keliver:konduit-*` (GitHub Packages, current)
to `io.github.waliasanchit007:konduit-*` (Maven Central, target).

Package names inside the JARs stay `dev.keliver.*` — adopters' Kotlin
`import` statements don't change. Only the Gradle coordinate string
changes. (Sonatype enforces groupId against the namespace; it does
not check internal package names.)

If a `konduit.<tld>` domain is ever acquired later, the project can
re-publish under the matching reverse-DNS namespace
(e.g. `run.konduit`) and the GitHub-vanity coordinates become a
legacy alias. Until then, `io.github.waliasanchit007` is the path
forward.

---

## Step 1 — Claim the `io.github.waliasanchit007` namespace at Sonatype Central

Modern Sonatype Central (2024+) flow — GitHub-based verification, no
DNS, no Jira tickets.

1. Go to [central.sonatype.com](https://central.sonatype.com) and
   sign in **with GitHub** (use the `waliasanchit007` account — the
   namespace ownership is bound to the OAuth-linked GitHub account).
2. Open the **Namespaces** page, click **Add Namespace**, enter
   `io.github.waliasanchit007`.
3. Sonatype detects the `io.github.<user>` shape and offers
   **GitHub verification**:
   - Sonatype displays a verification code (a short string like
     `abc12345`).
   - Create a *public* empty GitHub repo at
     `https://github.com/waliasanchit007/<verification-code>` (the
     repo name IS the code).
   - Back in Sonatype, click **Verify**. Sonatype checks that the
     repo exists under your username; verification is immediate.
   - Once verified, the namespace shows as "Verified" in the
     Namespaces table. You can delete the verification repo
     afterwards — Sonatype caches the result.
4. You can now publish under `io.github.waliasanchit007:*`
   coordinates.

**Lead time:** ~2 minutes end-to-end (vs ~1 day for DNS verification).
The only manual step is creating one throwaway public repo.

## Step 2 — Generate a GPG signing key

Maven Central requires every artifact be GPG-signed.

```bash
# Generate a key. Use a real name + your published-contact email.
gpg --full-generate-key
#   - Key type: RSA and RSA (default)
#   - Key size: 4096
#   - Expiry: 0 (never) — or set a 4-year expiry and rotate
#   - Real name: <Maintainer name>
#   - Email:    <published contact, e.g. walsan679@gmail.com>
#   - Passphrase: pick a long one, save in a password manager

# Find the key ID
gpg --list-secret-keys --keyid-format=long
#   sec   rsa4096/XXXXXXXXXXXXXXXX 2026-...

# Upload to a public keyserver so Maven Central can verify signatures
gpg --keyserver keyserver.ubuntu.com --send-keys XXXXXXXXXXXXXXXX
gpg --keyserver keys.openpgp.org    --send-keys XXXXXXXXXXXXXXXX

# Export the private key (for CI). KEEP THIS FILE SAFE — it's a secret.
gpg --export-secret-keys --armor XXXXXXXXXXXXXXXX > konduit-signing.key
```

## Step 3 — Configure GitHub repo secrets

In `https://github.com/waliasanchit007/konduit/settings/secrets/actions`,
add these four:

| Secret name | Value |
|---|---|
| `SONATYPE_USERNAME` | Sonatype Central user-token username (generate in the portal — recommended over the raw login) |
| `SONATYPE_PASSWORD` | Sonatype Central user-token password |
| `SIGNING_KEY` | Full contents of `konduit-signing.key` (the armored private key from Step 2) |
| `SIGNING_PASSWORD` | The passphrase from Step 2 |

## Step 4 — Build wiring (DONE — no action needed)

> **Status:** the in-repo build wiring below is already complete on
> `main`. This section is now a description of what exists, not a
> to-do list. The only things still pending are the three external
> steps above (namespace, GPG key, secrets) and then running the
> publish workflow (Step 4b).

1. **vanniktech plugin — applied.** `build-support`'s
   `RedwoodBuildPlugin.kt` applies
   [`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
   (`0.34.0`) to every published module. It calls
   `publishToMavenCentral(automaticRelease = true)` (uploads a single
   deployment bundle and auto-releases) and `signAllPublications()`,
   gated behind the `RELEASE_SIGNING_ENABLED` system property
   (default `true`; CI's GitHub-Packages job turns it off).
2. **Coordinates — configured, with an overridable groupId.** The
   plugin sets `coordinates(project.group, project.name,
   KONDUIT_VERSION)`. `project.group` comes from a `konduitGroupId()`
   helper that defaults to `dev.keliver` but is **overridable** with
   `-PkonduitGroupId=io.github.waliasanchit007`. So:
   - default builds + the GitHub Packages release keep `dev.keliver:*`
     (existing private consumers unaffected),
   - the Maven Central workflow passes the override to publish
     `io.github.waliasanchit007:*`.

   The override propagates to **inter-module dependencies** too — a
   `dev.keliver` artifact's POM references sibling modules by the same
   overridden group, so the Central artifacts are internally
   consistent (verified by inspecting the generated POM).
3. **POM metadata — Konduit-correct.** Every POM already carries
   `name`, `description`, `url`
   (`github.com/waliasanchit007/konduit`), Apache-2.0 `licenses`,
   `developers` (`waliasanchit007`), and `scm` — the full set Maven
   Central requires. (The old Cash App Redwood metadata was replaced
   during the fork; an earlier draft of this doc warned otherwise —
   that warning is obsolete.)

## Step 4b — Run the publish workflow (after Steps 1-3)

A dedicated, **manual** workflow does the release:
`.github/workflows/publish-maven-central.yml`.

- It is `workflow_dispatch`-only and **separate** from `publish.yml`
  (which keeps pushing `dev.keliver:*` to GitHub Packages on `v*`
  tags). The two channels co-exist; nothing about the private flow is
  blocked on Central credentials.
- It starts with a **preflight guard** that fails fast with a legible
  message if any of the four secrets from Step 3 are missing — so a
  premature run won't produce a confusing Gradle error.
- It passes `-PkonduitGroupId=io.github.waliasanchit007` and maps the
  four secrets onto the property names vanniktech expects
  (`mavenCentralUsername`/`Password`, `signingInMemoryKey`/`Password`).

To release once Steps 1-3 are done:

1. Bump `KONDUIT_VERSION` in `RedwoodBuildPlugin.kt` if needed and tag.
2. GitHub → Actions → **Publish to Maven Central** → **Run workflow**,
   passing the tag (or a commit ref) as the input.
3. Watch the run. POM/signature failures surface at the Sonatype side
   and are legible. First release of a new namespace can take a few
   minutes to index.

> **Caveat:** the workflow's wiring is verified locally only against
> `publishToMavenLocal` (coordinates, POM, signing-gate all correct).
> It has **not** been run end-to-end against the real Sonatype Central
> endpoint — that needs the credentials from Steps 1-3. Treat the
> first real run as the integration test, and expect to iterate on
> Sonatype-side validation messages.

## Step 5 — Update USAGE.md adoption instructions

Once `1.0.0-caliclan.4` is live on Maven Central:

1. Drop the `gpr.user` / `gpr.token` setup section from
   `docs/USAGE.md`.
2. Replace the `maven { url = ... }` GitHub Packages block with
   `mavenCentral()` (which most projects already have).
3. Update the version-catalog snippet:
   ```toml
   konduit-host  = { module = "io.github.waliasanchit007:konduit-host",  version.ref = "konduit" }
   konduit-guest = { module = "io.github.waliasanchit007:konduit-guest", version.ref = "konduit" }
   ```
4. Update the README's "Status" banner from "private fork" to
   "public OSS — `1.0.0-caliclan.N` on Maven Central."

## What can go wrong

- **GitHub-vanity verification repo conflict**: if a repo with the
  exact verification-code name already exists under `waliasanchit007`
  (unlikely — Sonatype's codes are random), delete or rename it
  first.
- **GPG key uploaded to wrong keyserver**: Maven Central polls
  `keyserver.ubuntu.com`, `keys.openpgp.org`, and a few others.
  Belt-and-braces it to at least two.
- **Missing POM metadata**: every artifact's POM must have name +
  description + url + license + developer + scm. The `vanniktech`
  plugin handles this and the POMs already carry the full Konduit set
  (see Step 4 #3), so this should be a non-issue — but it's the most
  common Central rejection, so eyeball the first generated POM if a
  run fails validation.
- **First release shows up but is "staged"**: Sonatype Central's
  default flow is **automatic publishing** now (no manual "release"
  button), but if you opted into the old flow you may need to click
  through. Check the portal after the first release.
- **Snapshot vs release naming**: `*-SNAPSHOT` versions go to a
  separate Snapshots repository on Sonatype Central, not the main
  index. Adopters need `https://central.sonatype.com/repository/maven-snapshots/`
  in their repos block to pull snapshots. The plugin handles routing.
- **Switching namespace later**: if you ever acquire a `konduit.<tld>`
  domain and want to switch from `io.github.waliasanchit007` to
  e.g. `run.konduit`, the path is: claim the new namespace at
  Sonatype, publish a new version line under the new coordinates,
  keep the GitHub-vanity coordinates published for at least one
  major version as a redirect alias. Adopters update their version
  catalog at their leisure.
