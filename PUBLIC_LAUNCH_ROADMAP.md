# Konduit — Public OSS Launch Roadmap

Tracks the work to take Konduit from "private fork shipping to Caliclan's own
apps" → "public OSS framework adoptable by external Compose Multiplatform
teams."

Current state: `1.0.0-caliclan.3` released to GitHub Packages (private),
end-to-end validated on Android via DevoStatus. Foundation is solid; the gap
is discoverability, documentation surface, packaging, and a public reference
implementation.

This is a living document. Check items off as they land.

---

## Phase 1 — Repo hygiene (no user action required)

These are things the integration team can ship without external dependencies
or GitHub-org changes.

- [ ] Rewrite `README.md` for Konduit (currently the upstream Cash App Redwood
      README — never rebranded). Must cover: what Konduit is, why a fork, what
      it gives you, the wire-format / API stability commitments, a 30-second
      "what does adoption look like" code sample.
- [ ] Move `docs/USAGE.md`, `docs/KNOWN_BUGS.md`, `docs/CHANGELOG.md`,
      `docs/HANDOVER.md` from the [ServerDrivenUI reference repo](https://github.com/waliasanchit007/ServerDrivenUI)
      into the Konduit fork so the docs travel with the artifact. (Keep
      ServerDrivenUI copies as redirects or delete entirely once the Konduit
      copies are canonical.)
- [ ] `CONTRIBUTING.md` — how to file an issue, how to open a PR, the local
      dev loop, the test policy.
- [ ] `CODE_OF_CONDUCT.md` — Apache 2.0 / Contributor Covenant.
- [ ] `.github/ISSUE_TEMPLATE/` — bug-report, feature-request, integration-help.
- [ ] `.github/PULL_REQUEST_TEMPLATE.md`.
- [ ] Update GitHub repo description + topics + homepage URL.

## Phase 2 — Compose Facade (one-PR API surface reduction) — ✅ landed

Adopters previously imported from 8+ Konduit modules (`konduit-treehouse-host`,
`konduit-treehouse-host-composeui`, `konduit-compose`, `konduit-widget`,
`konduit-runtime`, `konduit-protocol`, `konduit-protocol-host`,
`konduit-treehouse`) plus the two Zipline artifacts. This is friction
for first-time adopters.

Shipped in `1.0.0-caliclan.4` as two facade modules instead of one
(`konduit-host` for adopter host modules, `konduit-guest` for guest
modules — matches the existing host/guest split in
`konduit-treehouse-host` vs `konduit-treehouse-guest`).

- [x] `dev.konduit:konduit-host` facade — TreehouseHost target group,
      `api`-exposes all host-side modules + Zipline.
- [x] `dev.konduit:konduit-guest` facade — TreehouseGuest target group,
      `api`-exposes all guest-side modules + Zipline.
- [x] USAGE.md updated to lead with `libs.konduit.host` and
      `libs.konduit.guest` catalog references.
- [x] Migration note included in USAGE.md (the pre-facade per-module
      imports continue to work; the facade is additive).

## Phase 3 — iOS demo validation (the production-readiness gap)

DevoStatus currently has no iOS UI. "iOS works" is backed only by
`compileKotlinIosSimulatorArm64` passing — no real-world end-to-end. The
first external adopter targeting iOS will hit something.

- [ ] Wire DevoStatus's iOS host: `KonduitIosHost.kt` already exists in
      `konduit-host/src/iosMain` but doesn't have a SwiftUI / UIKit entry
      point. Add a minimal iOS app that mounts at least the Quotes tab.
- [ ] Verify the full host-service stack on iPhone simulator: HostConsole,
      HostSnackbar, HostQuotesProvider, HostWallpapersProvider,
      HostExploreSaver (the bitmap pipeline will need iOS-equivalent
      Photos-library save instead of MediaStore).
- [ ] Update KNOWN_BUGS / USAGE to call out anything new that surfaces.

## Phase 4 — Public sample app

External adopters need a runnable reference. DevoStatus is private, so a
**public** Konduit-Sample needs to exist.

- [ ] Extract a 1–2 screen sample from DevoStatus into a new public repo
      (`waliasanchit007/konduit-sample`) OR commit it inside the Konduit
      repo under `sample/`. Latter is simpler for discoverability.
- [ ] Sample covers: host setup (Android + iOS), `HostConsole`, one provider
      service, one navigator callback, one screen with widgets.
- [ ] CI runs the sample on every PR (assembleDebug + iOS framework link).

## Phase 5 — Maven Central publishing (user-action gated)

GitHub Packages requires consumers to authenticate with a PAT. Maven Central
is the standard for OSS. Once on Maven Central, `implementation("dev.konduit:konduit:1.0.0-...")`
just works for anyone.

- [ ] **USER ACTION**: Create a Sonatype Central account at
      [central.sonatype.com](https://central.sonatype.com), claim the
      `dev.konduit` namespace (DNS TXT verification or whatever Sonatype's
      flow is in 2026).
- [ ] **USER ACTION**: Generate a GPG signing key and add to keyserver.
      Add the private key + passphrase as GitHub repo secrets.
- [ ] Update `publish.yml` GHA workflow to target Maven Central in addition
      to (or instead of) GitHub Packages.
- [ ] First test release: cut `1.0.0-caliclan.4` to Maven Central, verify
      consumers can resolve without auth.
- [ ] Update USAGE.md adoption instructions: drop the
      `gpr.user`/`gpr.token` step, replace with vanilla `mavenCentral()`.

## Phase 6 — Public visibility (user-action gated)

- [ ] **USER ACTION**: Flip Konduit fork visibility from **private → public**
      via GitHub repo settings. Triple-check there are no secrets, internal
      paths, or proprietary context in commit history (we don't think there
      are, but worth one careful pass).
- [ ] **USER ACTION**: Flip DevoStatus visibility (optional — depends on
      whether DevoStatus stays the integration test or becomes a public
      reference).
- [ ] Update `README.md` "Status" banner from "private fork" → "public OSS,
      `1.0.0-caliclan.N` semver".
- [ ] Post launch announcement (Twitter / r/androiddev / Kotlin Slack / dev.to)
      with the "what + why + how to adopt" pitch.

## Phase 7 — Docs site (user-action gated for hosting choice)

`USAGE.md` is good but a flat markdown file scales poorly past ~1k lines.
A real docs site with navigation + search dramatically improves adopter
onboarding.

- [ ] **USER ACTION**: Decide hosting — GitHub Pages (free, simple),
      Vercel/Netlify (free for OSS, more flexible), or a dedicated domain.
- [ ] Decide stack — [MkDocs](https://www.mkdocs.org/) (simple, Python),
      [Docusaurus](https://docusaurus.io/) (more features, React),
      [Writerside](https://www.jetbrains.com/writerside/) (JetBrains, native
      Kotlin alignment), or hand-rolled.
- [ ] Migrate USAGE.md → site structure: Getting Started, Concepts,
      How-to Guides, Reference, Troubleshooting, Changelog.
- [ ] Auto-publish on every tag via GHA.

## Phase 8 — Migration guide from Cash App Redwood

Many CMP teams have evaluated upstream Redwood (which is sunsetted). Konduit
is the natural fork they should consider — but the migration story has to be
spelled out.

- [ ] Cataloging of API differences between upstream Redwood `0.18.0` (the
      fork point) and Konduit `1.0.0-caliclan.3+`. Most are renames
      (`app.cash.redwood.*` → `dev.konduit.*`); some are deliberate trims
      (removed View/UIView/DOM modules in `1.0.0-caliclan.2`).
- [ ] A scripted rename helper for adopters with existing Redwood codebases.
- [ ] Side-by-side examples for the 5 most common Redwood patterns.

## Phase 9 — Roadmap + API stability commitment

OSS adopters need to know what's stable and what's still moving. Without a
public commitment they can't justify the build-out cost.

- [ ] Public `ROADMAP.md` (different from this file) showing what's planned
      next: Compose Facade, more widgets, performance work, etc.
- [ ] Wire-format / API stability policy — what's covered by SemVer, what's
      "experimental", how breaking changes get deprecated.
- [ ] LTS / support window commitment — which versions get bug fixes for
      how long.

## Phase 10 — Performance benchmarks

Adopters need confidence that the QuickJS + Zipline indirection isn't a
performance cliff vs native.

- [ ] Benchmark suite: cold-start (host process → first widget renders),
      warm-mount (tab switch → render), update latency (host state change →
      widget reflects), memory footprint.
- [ ] Comparison baseline: native Compose for the same screen, upstream
      Redwood `0.18.0` if practical.
- [ ] CI runs benchmarks on every release tag; results published with the
      release notes.

---

## Honest effort estimate

| Phase | Effort | Blocker |
|---|---|---|
| 1 Repo hygiene | 1 day | None |
| 2 Compose Facade | 2–3 days | None |
| 3 iOS demo | 2–3 days | iOS dev environment |
| 4 Sample app | 1–2 days | Depends on Phase 2 |
| 5 Maven Central | 2 days + Sonatype lead time | User action: Sonatype account + GPG key |
| 6 Public visibility | 1 hour | User action: GitHub setting |
| 7 Docs site | 3–5 days | User action: hosting decision |
| 8 Migration guide | 2 days | None |
| 9 Roadmap + API stability | 1 day | None |
| 10 Benchmarks | 3–5 days | None |

**Total**: ~3 weeks of focused work plus user-action turnaround for Sonatype
(typically a few business days for namespace verification).

## What unlocks the "live" claim

Phases 1, 2, 3, 4, 5, 6 are the **minimum viable launch**. Phases 7–10 are
post-launch quality improvements that can roll out incrementally after the
public announcement.
