# Maven Central setup checklist (Phase 5)

Tracks the user-action steps to move Konduit from **GitHub Packages
(private, requires PAT)** to **Maven Central (public, no auth)**.
Phase 5 of [PUBLIC_LAUNCH_ROADMAP.md](../PUBLIC_LAUNCH_ROADMAP.md).

Once this is done, downstream consumers can drop
`implementation("dev.konduit:konduit:1.0.0-...")` into a Gradle build
with `mavenCentral()` configured and just have it work — no
`gpr.user` / `gpr.token` step, no GitHub Personal Access Token.

This is gated on **two manual steps the user has to do**: claim the
namespace at Sonatype, and generate a GPG signing key. The rest can
be wired up by the implementation team once those two artifacts exist.

---

## Step 1 — Claim the `dev.konduit` namespace at Sonatype Central

Modern Sonatype Central (2024+) flow — no Jira tickets anymore.

1. Go to [central.sonatype.com](https://central.sonatype.com) and
   sign up. The account is what publishes; pick an email that's
   monitored long-term.
2. Open the **Namespaces** page, click **Add Namespace**, enter
   `dev.konduit`.
3. Sonatype offers two verification paths:
   - **Domain TXT record** (preferred — owns the namespace for good):
     buy `konduit.dev` from any registrar, add a TXT record at the
     apex with the verification token Sonatype provides. ~1–2 hour
     DNS propagation, then click "verify."
   - **Use your GitHub username as a vanity namespace**: requires no
     DNS but locks you into `io.github.<username>` — not what we
     want for a real project namespace. Skip.
4. Once verified, the namespace shows as "Verified" in the
   Namespaces table. You can now publish under
   `dev.konduit:*` coordinates.

**Lead time:** the *verification* is instant once DNS propagates. The
domain purchase + DNS edit is the slow part — budget ~1 day if you
don't already own `konduit.dev`.

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
| `SONATYPE_USERNAME` | Sonatype Central username from Step 1 |
| `SONATYPE_PASSWORD` | Sonatype Central password (or a user-token if you generate one in the portal — recommended over the raw password) |
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
2. Configure publishing coordinates: `groupId = "dev.konduit"`,
   `artifactId = <module name>`, version from
   `RedwoodBuildPlugin.KONDUIT_VERSION`.
3. Add POM metadata: `name`, `description`, `url`, `licenses`,
   `developers`, `scm`. Maven Central rejects POMs missing any of
   these.
4. Update `publish.yml` to pass `SIGNING_KEY` /
   `SIGNING_PASSWORD` / `SONATYPE_USERNAME` /
   `SONATYPE_PASSWORD` as Gradle properties.
5. First test release: cut `1.0.0-caliclan.4` and watch the
   GHA run. Failures usually surface as POM validation errors at
   the Sonatype side — they're legible.

## Step 5 — Update USAGE.md adoption instructions

Once `1.0.0-caliclan.4` is live on Maven Central:

1. Drop the `gpr.user` / `gpr.token` setup section from
   `docs/USAGE.md`.
2. Replace the `maven { url = ... }` GitHub Packages block with
   `mavenCentral()` (which most projects already have).
3. Update the README's "Status" banner from "private fork" to
   "public OSS — `1.0.0-caliclan.N` on Maven Central."

## What can go wrong

- **Namespace claim takes > 1 day**: Sonatype's DNS verification is
  instant once propagated, but the propagation itself can take longer
  than you'd expect on a fresh domain. Buy the domain first, do
  everything else in parallel.
- **GPG key uploaded to wrong keyserver**: Maven Central polls
  `keyserver.ubuntu.com`, `keys.openpgp.org`, and a few others.
  Belt-and-braces it to at least two.
- **Missing POM metadata**: every artifact's POM must have name +
  description + url + license + developer + scm. The `vanniktech`
  plugin makes this easy; manual `publishing {}` blocks are
  error-prone.
- **First release shows up but is "staged"**: Sonatype Central's
  default flow is **automatic publishing** now (no manual "release"
  button), but if you opted into the old flow you may need to click
  through. Check the portal after the first release.
- **Snapshot vs release naming**: `*-SNAPSHOT` versions go to a
  separate Snapshots repository on Sonatype Central, not the main
  index. Adopters need `https://central.sonatype.com/repository/maven-snapshots/`
  in their repos block to pull snapshots. The plugin handles routing.
