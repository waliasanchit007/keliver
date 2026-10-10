# keliver-portal-tools 0.3.5 — release notes

**Status: RELEASED 2026-09-22** —
<https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.5>.
Tools 0.3.4 and the Maven 0.3.3 libraries are unchanged; GitHub's "Latest"
badge stays on the library release `v0.3.3`.

| | |
|---|---|
| tag | `portal-tools-v0.3.5` → **`b5615637531127c1d408fe0f7d652cac044d2930`** (the `sourceCommit` in `VERSION.json`; not the merge commit) |
| merged | PR #80, merge commit `b96a9e2eb`, head matched server-side |
| asset | `keliver-portal-tools-0.3.5.zip`, 90,351,569 bytes, plus `.sha256` |
| zip sha256 | `4e1c3040c2e069ab2a503f4b45a7740a28ac243edfb233cdffc7bcb7e7eeb439` |
| APK sha256 | `475276a4c51c90698871acd0f405a73678c4b96b8cff37b773fe508e5faff04e` (no embedded portal key) |
| build run | [`35788740086`](https://github.com/waliasanchit007/keliver/actions/runs/35788740086), retained artifact `10721358747` |
| device run | [`35790340108`](https://github.com/waliasanchit007/keliver/actions/runs/35790340108) — 19/0 device checks, 23/0 packaged acceptance, API 33 x86_64 emulator |
| tag-push run | [`35796515942`](https://github.com/waliasanchit007/keliver/actions/runs/35796515942) — the read-only rebuild the tag fires; its artifact was **not** attached |

**How the stored asset was checked.** The published asset is the retained
artifact uploaded byte-for-byte. Its hash was confirmed at the retained artifact,
at upload, and by GitHub's server-side `digest` of the stored draft asset. A
download-back of the draft could **not** be run: the releasing machine's network
blocks `release-assets.githubusercontent.com` (see `PORTAL_TOOLS_RELEASE.md`
step 4). The **public** download was then checked from a GitHub-hosted runner,
against the release's own `.sha256` and the pinned hash, by the first step of
`reference-app.yml` (runs `35800642819` and `35802020305`: `keliver-portal-tools-0.3.5.zip: OK`,
`4e1c3040…`).

The candidate text below is unchanged from the tagged commit, where it was
written before these values existed.

---

## What this release is

**Tools 0.3.5 uses Maven libraries 0.3.3.** The library line has not moved; only
the tools bundle has. `VERSION.json` inside the ZIP records both versions and the
commit the bundle was built from.

It is the first bundle in which **an app keeps one signing identity when it
moves**, and in which the tools **refuse rather than guess** when they can't
establish that identity.

## Store identity and recovery

**U23 — a rename silently rotated your signing identity.** The store was derived
from the app's path, so `mv ~/work/myapp ~/work/checkout` resolved to a
*different* store, found no keys, and generated a new Ed25519 keypair. Bundles
published before the move stopped verifying, and a production host built earlier
rejected newly signed manifests. Nothing was destroyed: the old store, keypair
included, was still on disk. But nothing told you so.

**U24 — the error message recommended a recovery the relay then refused.** It
told you to point `"store"` at the directory holding your identity. Doing that
made the relay refuse to boot, because the store "already belongs to another
app". The other app was the same one.

**U25.1 — a symlink split one app across two identities.** Launching through
`~/work/current` or through its target `~/work/app-v2` resolved two different
stores, with no warning.

**What changed.** An app's identity is now **the store it is bound to**, not its
path, directory name or Git remote. The binding has two halves: an `owner` marker
in the store and a pointer in `<app>/.gradle/`. A path mismatch is **refused
loudly**, and the refusal names the recovery command. Both halves of the default
store name come from the canonical path, so a symlink and the real directory
count as one app. The full contract is
[`STORE_IDENTITY.md`](STORE_IDENTITY.md).

**A failed start no longer claims a store.** The relay checks the pointer's
destination before claiming anything, and rolls back if it fails between that
check and the write. A failed start leaves no new identity and no misleading
"split" candidate behind.

## The tools refuse rather than guess the home directory (#78)

Every path the tools resolve for your app is relative to a **home directory**.
That home picks the default store and expands a `~/...` in `keliver.portal.json`.
The store holds the signing key and the public key a device host embeds, so
resolving against the wrong home means signing with one identity and verifying
against another.

The relay's reference is the JVM's `user.home`. **`$HOME` is not a substitute
for it**: on macOS, `user.home` comes from your account record and ignores
`$HOME`, so the two are often different directories.

**Before 0.3.5**, `keliver-store-path.sh` asked `java` for `user.home` in a way
that lost java's exit status. If java was missing, crashed, or printed no
`user.home`, the resolver silently answered using `$HOME` instead.

**In 0.3.5**, when resolving your store requires discovering the JVM home and
that discovery fails, the tools **refuse, exiting 4**, instead of guessing from
`$HOME`. The message says what failed and names both fixes:

```
put a working java on PATH, or pass --home <dir>
```

Discovery counts as failed when java can't be run, exits non-zero, or doesn't
report exactly one non-empty absolute `user.home`.

**Explicit configurations that don't need discovery work exactly as before**,
including on a machine with no java:

* **`PORTAL_STORE=<dir>`** is answered before any home is read. A value that is
  empty or only whitespace now counts as *not set*, matching the relay, which
  already ignored it.
* **`--home <absolute dir>`** is used as given, with no discovery. A *relative*
  `--home` is now a usage error (exit 2), because it would otherwise depend on
  whichever directory you happened to run the command from.
* **A Gradle build of your app** passes the running JVM's own `user.home`, so it
  never goes through discovery and can't disagree with the relay.

`--default` and a `~/...` store in `keliver.portal.json` *do* depend on the home,
so they refuse when it can't be established.

**What you might notice.** On a machine without a working java, the bundled
tools now refuse where they used to answer:

| tool | exit | notes |
|---|---|---|
| `bin/keliver-store-path.sh` | **4** | |
| `bin/keliver-record-http.sh` | **4** | |
| `bin/keliver-adopt-legacy-store.sh` | **4** | |
| `bin/keliver-store-recover.sh` | **1** | uses its own failure code, but passes the resolver's message through |

All four print the same fix. On Linux, where `$HOME` and `user.home` usually agree,
the old answer was usually right. The refusal is the price of never being
silently wrong on macOS, where they don't agree. A JDK 17+ was already a
documented prerequisite of this bundle.

**The bundled tools now also check the resolver's answer before acting on it.**
When a resolver refusal arrived as an empty path, earlier versions of the
bundled tools carried on regardless:

* `keliver-adopt-legacy-store.sh` targeted `/<file>` and tried to copy a legacy
  **private signing key** to `/keys/ed25519.priv`, printed `copied:` and exited
  0. It now exits non-zero, having copied nothing. A failed copy is also
  reported as a failure now, not counted as a success.
* `keliver-record-http.sh` reported "recording token not found; start the relay
  with `PORTAL_HTTP_RECORD=1`", sending you to restart a relay that was running
  fine. It now says the store couldn't be resolved, and that nothing was sent.

## Device host input validation

* **A malformed embedded portal key is refused before anything is loaded**,
  including a key of the wrong length.
* **`-Pkeliver.devOnlyHost` accepts `true 1 yes on` and `false 0 no off`**, in
  any case, **and rejects anything else**. Before, Groovy's `toBoolean()` read
  `yes` and `on` as *false*, so `-Pkeliver.devOnlyHost=yes` quietly built a
  production-shaped host. A bare flag with no value is rejected too.

## Signing and builds

* **A published guest bundle can no longer ship unsigned while a key is
  present.** Whether the signing key got applied depended on where one statement
  sat in the guest's build file, and in the wrong place the bundle compiled
  unsigned without any error. It's fixed, and a check fails if that statement
  moves back.
* **An unresolvable store fails only the tasks that need your identity.** Store
  resolution used to run during Gradle's configuration phase, so a resolver that
  couldn't answer failed *every* task, `:portal-relay:test` and `apiCheck`
  included. Now only the tasks that embed or sign with an identity refuse, and
  they name the resolver and its exit code.

## Recovery

### Prerequisites

* **A JDK 17+ on `PATH`**, or pass `--home <dir>` explicitly. See #78 above:
  without one, the recovery tool refuses rather than guessing the home.
* **Stop the portal first.** Recovery underneath a running relay isn't
  supported; the lock only covers relay startup.
* **Know which app directory is being recovered**, as an absolute path.

### If you move or rename an app

```bash
$KP/keliver-store-recover.sh /abs/path/to/the/app
```

It rewrites the store's owner marker and the app's pointer as one transaction,
then **runs the resolver and checks it now selects that store**. If either write
or that check fails, or you interrupt it, the previous binding is restored byte
for byte and checked before the tool says so, and it exits non-zero. It never
reads, copies or regenerates key material. It prints the public-key fingerprints
before and after, so you can see nothing changed. Bundles signed before the move
still verify afterwards.

It refuses, changing nothing, in three cases:

* the recorded owner still exists and still uses that store (that's two apps,
  not a move);
* this app already has a store of its own holding an identity;
* `keliver.portal.json` pins the app to a different store.

`--help` works as the first argument and changes nothing.

**If the directory is a copy rather than a move**, delete the pointer it
inherited, and it will start its own store:

```bash
rm /abs/path/to/the/copy/.gradle/keliver-store-path
```

**If the portal reports a store "split"**, the app was launched through both a
symlink and the real directory before 0.3.5. The report lists both stores with
their public-key fingerprints. Pick the one your published bundles verify
against:

```bash
$KP/keliver-store-recover.sh /abs/path/to/the/app --store ~/.keliver-portal/apps/<the one to keep>
```

The other store isn't read, moved or deleted.

## Upgrading from 0.3.4

There's no migration step, and nothing rewrites an existing store.

* An app that has booted and hasn't moved is unaffected.
* An app with no pointer, at an unchanged path, is adopted automatically.
* **An app already split across a symlink refuses to start**, deliberately.
  It has two identities, and picking one silently is the bug. The refusal lists
  both stores and the command to choose one.
* An app renamed under 0.3.4 that already created a second identity starts
  normally on that second identity. Getting the original back means moving the
  new store aside first.
* **On a machine with no working java**, the bundled scripts listed under #78
  refuse instead of answering (exit 4, or 1 for `keliver-store-recover.sh`).
  Install a JDK 17+ or pass `--home`.

## Known limitations

* **Recovery underneath a running relay is unsupported.** Stop the portal first.
* **`SIGKILL` and power loss can't be rolled back.** A per-run backup under
  `<app>/.gradle/` survives and is deleted only once a recovery has been checked.
* Process-ID reuse, or a process-ID namespace from another container, can still
  defeat the stale-lock check. A bounded wait, and a refusal that names the
  directory, are the backstop.
* `PORTAL_STORE` doesn't rebind an app, but it does claim the store permanently,
  so use a throwaway directory.
* `-Pkeliver.portalStore` is a **build-only override that warns rather than
  checks**. It can put the publisher's identity out of step with the relay's,
  and it bypasses the split refusal.
* **The iOS host's key generation isn't hardened like the Android one** (#77).
  Its generated source directory can keep a stale or planted file across an
  up-to-date build. It's recorded and has a reproduction plan, but **it is not
  fixed in 0.3.5**.
* Device verification for this candidate covers **Android API 33, x86_64, on an
  emulator**. No physical device and no iOS target has been verified.
* U19 (the live-preview re-render; cause unresolved) and U20 (editor frame
  rate; measured but not assessed) are **unchanged and still open**. Nothing in
  0.3.5 fixes them. (Corrected after publication: this line first also listed
  U25.2–.4 as open. Those three — the key-length bound, `devOnlyHost` parsing
  and the store-resolver fallback — **are** fixed in 0.3.5, as the sections
  above describe.)
