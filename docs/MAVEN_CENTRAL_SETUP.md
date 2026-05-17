# Maven Central setup checklist (Phase 5)

Tracks the user-action steps to move Konduit from **GitHub Packages
(private, requires PAT)** to **Maven Central (public, no auth)**.
Phase 5 of [PUBLIC_LAUNCH_ROADMAP.md](../PUBLIC_LAUNCH_ROADMAP.md).

Once this is done, downstream consumers can drop
`implementation("io.github.waliasanchit007:konduit-host:1.0.0-...")`
into a Gradle build with `mavenCentral()` configured and just have it
work — no `gpr.user` / `gpr.token` step, no GitHub Personal Access Token.

This is gated on **two manual steps the user has to do**: claim the
namespace at Sonatype, and generate a GPG signing key. The rest can
be wired up by the implementation team once those two artifacts exist.

## Namespace decision

The project is publishing under the **`io.github.waliasanchit007`**
Sonatype namespace (GitHub-vanity flow — no domain required).
Artifacts go from `dev.konduit:konduit-*` (GitHub Packages, current)
to `io.github.waliasanchit007:konduit-*` (Maven Central, target).

Package names inside the JARs stay `dev.konduit.*` — adopters' Kotlin
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

## Step 4 — Update `publish.yml` (this is the implementation work)

Currently `.github/workflows/publish.yml` targets GitHub Packages.
The migration:

1. Add the
   [`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
   plugin to the build-support module (it handles Sonatype Central
   uploads + signing in one place). Or alternatively wire
   `signing` + `publishing` blocks directly per module.
2. Configure publishing coordinates:
   - `groupId = "io.github.waliasanchit007"`
   - `artifactId = <module name>` (unchanged — `konduit-host`,
     `konduit-guest`, etc.)
   - version from `RedwoodBuildPlugin.KONDUIT_VERSION`
3. Add POM metadata: `name`, `description`, `url`, `licenses`,
   `developers`, `scm`. Maven Central rejects POMs missing any of
   these. **Currently the POMs still inherit Cash App Redwood
   metadata** (`<url>cashapp/redwood</url>`, `<developer>cashapp`,
   etc.) — this MUST be replaced with Konduit / waliasanchit007
   values before the first Maven Central release.
4. Update `publish.yml` to pass `SIGNING_KEY` /
   `SIGNING_PASSWORD` / `SONATYPE_USERNAME` /
   `SONATYPE_PASSWORD` as Gradle properties.
5. **Option:** keep the existing GitHub Packages publish step too —
   double-publishing means existing private consumers of
   `dev.konduit:*` keep working unchanged while new public consumers
   pick up `io.github.waliasanchit007:*`. Drop the GitHub Packages
   step when the migration is complete.
6. First test release: cut `1.0.0-caliclan.4` and watch the GHA run.
   Failures usually surface as POM validation errors at the
   Sonatype side — they're legible.

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
  plugin makes this easy; manual `publishing {}` blocks are
  error-prone. The current POMs inheriting Cash App Redwood metadata
  will be rejected — Step 4 #3 is mandatory, not optional.
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
