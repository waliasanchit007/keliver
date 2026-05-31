# Maven Central setup checklist (Phase 5)

Tracks the user-action steps to move Keliver from **GitHub Packages
(private, requires PAT)** to **Maven Central (public, no auth)**.
Phase 5 of [PUBLIC_LAUNCH_ROADMAP.md](../PUBLIC_LAUNCH_ROADMAP.md).

Once this is done, downstream consumers can drop
`implementation("dev.keliver:keliver-host:1.0.0-...")`
into a Gradle build with `mavenCentral()` configured and just have it
work — no `gpr.user` / `gpr.token` step, no GitHub Personal Access Token.

**The in-repo build wiring is done** (vanniktech plugin, Central
coordinates with an overridable groupId, Keliver-correct POMs, gated
signing, and a manual `publish-maven-central.yml` workflow — see
Steps 4 / 4b). What remains is **three external steps only the
maintainer can do**: claim the namespace at Sonatype, generate a GPG
signing key, and add the four repo secrets. After that, releasing is
a single manual workflow run.

## Namespace decision

The project publishes under its own reverse-DNS namespace
**`dev.keliver`**, backed by the **keliver.dev** domain. The *same*
coordinate is used for both GitHub Packages and Maven Central — no
vanity namespace and no per-channel groupId override.

Crucially, the Gradle group **and** the plugin IDs are both
`dev.keliver.*`, so *every* artifact — including the Gradle plugin
marker publications
(`dev.keliver.schema:dev.keliver.schema.gradle.plugin`, etc.) — falls
under this one owned namespace.

> **Why a real domain, not the GitHub-vanity namespace.** An earlier
> plan used `io.github.waliasanchit007` (no domain required). It worked
> for the library modules, but a deployment containing the Gradle plugin
> markers would have been **rejected**: a marker's coordinate is fixed by
> its plugin ID (`dev.keliver.*`), not by the publishing group, so it
> could never sit under `io.github.*`. Owning `dev.keliver` via keliver.dev
> removes that constraint — the whole project, plugin included, publishes
> cleanly under one namespace.

---

## Step 1 — Claim + verify the `dev.keliver` namespace at Sonatype Central

DNS-based verification (you own **keliver.dev**):

1. Go to [central.sonatype.com](https://central.sonatype.com) and sign in.
2. Open the **Namespaces** page, click **Add Namespace**, enter
   `dev.keliver`.
3. Sonatype shows a **verification key** (a token string). Add it as a
   **TXT record** on the apex of `keliver.dev` at your DNS provider:

   ```
   keliver.dev.   TXT   "<verification-key-from-sonatype>"
   ```

   Use a low TTL so it propagates quickly.
4. Back in Sonatype, click **Verify**. Once DNS propagates (usually
   minutes, up to ~1 hour), the namespace shows **Verified**. You can
   delete the TXT record afterwards — Sonatype caches the result.
5. You can now publish `dev.keliver:*` to Central.

**Reusable from the earlier setup:** the GPG signing key and the four
repo secrets (Steps 2-3) are **already provisioned** and need no change —
a Sonatype user token works for any namespace the account owns, so it
covers `dev.keliver` once verified.

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
gpg --export-secret-keys --armor XXXXXXXXXXXXXXXX > keliver-signing.key
```

## Step 3 — Configure GitHub repo secrets

In `https://github.com/waliasanchit007/keliver/settings/secrets/actions`,
add these four:

| Secret name | Value |
|---|---|
| `SONATYPE_USERNAME` | Sonatype Central user-token username (generate in the portal — recommended over the raw login) |
| `SONATYPE_PASSWORD` | Sonatype Central user-token password |
| `SIGNING_KEY` | Full contents of `keliver-signing.key` (the armored private key from Step 2) |
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
2. **Coordinates — `dev.keliver` everywhere.** The plugin sets
   `coordinates(project.group, project.name, KELIVER_VERSION)` and
   `project.group` defaults to `dev.keliver`. Both GitHub Packages and
   Maven Central publish under that one group — no per-channel override.
   (A `keliverGroupId` Gradle property still exists as a general escape
   hatch for forks, but the release path doesn't use it.) Inter-module
   dependency POMs reference siblings by the same `dev.keliver` group, so
   the artifact set is internally consistent.
3. **POM metadata — Keliver-correct.** Every POM already carries
   `name`, `description`, `url`
   (`github.com/waliasanchit007/keliver`), Apache-2.0 `licenses`,
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
- It publishes under the default `dev.keliver` group (no override) and
  maps the four secrets onto the property names vanniktech expects
  (`mavenCentralUsername`/`Password`, `signingInMemoryKey`/`Password`).

To release once Steps 1-3 are done:

1. Bump `KELIVER_VERSION` in `RedwoodBuildPlugin.kt` if needed and tag.
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
   keliver-host  = { module = "dev.keliver:keliver-host",  version.ref = "keliver" }
   keliver-guest = { module = "dev.keliver:keliver-guest", version.ref = "keliver" }
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
  plugin handles this and the POMs already carry the full Keliver set
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
- **DNS TXT record removed too early**: leave the verification TXT
  record on `keliver.dev` in place until Sonatype shows the namespace
  **Verified**. Removing it before verification completes (DNS can take
  up to ~1 hour to propagate) makes the check fail; just re-add it and
  retry. Once Verified, Sonatype caches the result and the record can go.
