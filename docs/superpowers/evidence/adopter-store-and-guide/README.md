# Two adopter defects: shared document store, and a guide that shipped nowhere

Both reproduced first, from disposable apps outside this checkout, through the
ordinary adopter startup path (`PORTAL_REPO=<app> <bundle>/relay/bin/portal-relay`,
which is exactly what `bin/keliver-portal` runs).

Fixed in `a3b9651ad`.

## A. Cross-project contamination

### Reproduced

Two apps scaffolded by `keliver-init` (`appx` with screen `orders`, `appy` with
screen `profile`), a disposable store, the stock configuration.

With **both relays running** — the ordinary two-project situation:

```
appy: ["orders"]        <- appy lists the OTHER app's screen; its own is gone
appx: ["orders"]
GET /doc?screen=orders on appy -> 200, and appy's source tree gains
    src/jsMain/kotlin/screens/orders.kt
    src/jsMain/kotlin/screens/Compiled_orders.kt
```

That is the same shape as the `feed.kt` / `Compiled_feed.kt` leak seen while
setting up M4 case 2.

**Booting one app after the other is destructive too**: `bootScan` retires
store mirrors whose `.kt` is missing from the app it is serving, so starting
appy deleted appx's `default/orders.json` (logged:
`retired stale store mirror default/orders`).

Regression before the fix: **7 passed, 2 failed**
([`regression-before-fix.log`](regression-before-fix.log)).

### Root cause

`storeDir()` returned `~/.keliver-portal` — one machine-global directory for
every app. Nothing tied a store to a repo, so both of the store's own
invariants (retire stale mirrors; materialise a document's backing `.kt`)
became cross-app operations.

### Intended ownership rules, now implemented

1. A document store belongs to **exactly one** app repo.
2. Default location: `~/.keliver-portal/apps/<slug>-<8 hex of the repo's
   absolute path>` — inside the familiar global root, owned by one repo.
3. The store is **never** inside the adopter's source tree.
4. It persists across restarts for the same repo.
5. An explicit `store` (or `PORTAL_STORE`) is honoured, but the store records
   its owner and **refuses to serve a second repo**, with an error that names
   both paths and the fix ([`store-conflict-refusal.log`](store-conflict-refusal.log)).
6. A request for a screen the app does not have is **404**, not an invitation
   to invent one.

Rule 6 is what actually stopped the file appearing: `/doc` for an unknown
screen used to mint a document, and the engine materialises its backing `.kt`.
This is the server-side half of `KNOWN_BUGS` U16.

**`PORTAL_STORE` was silently ignored** before this change — it reads as
isolation and was not. It is now honoured, and subject to the same ownership
check.

Regression after the fix: **9 passed, 0 failed**
([`regression-after-fix.log`](regression-after-fix.log)).

### Verified from the candidate bundle, outside the repo

`keliver-portal-tools-0.3.3-local`, two freshly scaffolded apps, disposable
`user.home`:

```
alpha: ["invoice"]   beta: ["home"]      <- both running, neither sees the other
beta GET /doc?screen=invoice -> HTTP 404
    {"error":"no screen 'invoice' in project 'default'; known screens: home"}
beta source tree UNCHANGED
stores: ~/.keliver-portal/apps/alpha-f1c9cd4f, ~/.keliver-portal/apps/beta-efcb5691
beta after restart: ["home"], same store reused
```

## B. `get_guide` returned "guide not found" for every adopter

### Reproduced

Staged MCP package, fresh scaffold, cwd = the app:

```
guide not found at <app>/docs/PORTAL_USAGE.md
```

`docs/PORTAL_USAGE.md` exists in **this repository** and in no scaffolded app,
and the package shipped no markdown at all — so the tool worked when run from
the Keliver checkout and failed everywhere else. It was reported as unhelpful
by the M4 case-2 diagnostic participant.

### Fixed

The guide is copied into the package at build time from `docs/PORTAL_USAGE.md`
(a Gradle `Copy` into the resources of `portal-mcp`), so it cannot drift from
the canonical file. Resolution order: the app's own `docs/PORTAL_USAGE.md` if
it has one (a team can document its own conventions), then the bundled copy,
then a message that says what is missing.

### Verified through the packaged server

```
fresh scaffold, PORTAL_REPO set   -> ok, 14156 chars, "# Keliver Portal — Usage Guide"
/tmp, no PORTAL_REPO, no app      -> ok, 14156 chars
bundled portal-mcp-0.3.3.jar      -> dev/keliver/portal/mcp/PORTAL_USAGE.md present
```

## Damage I caused — partially repaired

My first reproduction set `HOME` to a disposable directory and assumed that
redirected the store. **It does not**: `storeDir()` expands `~/` through the
JVM's `user.home` system property, which macOS derives from the passwd entry,
not from `$HOME`. That run wrote into the developer's real
`~/.keliver-portal`.

**Partially repaired**, and the parts differ:

| what | state |
|---|---|
| **Signing keys** (`keys/ed25519.priv`, `.pub`, dated Sep 5) | **never touched** — not read, not written, not inspected |
| **Bundles** (`bundles/`) | **never touched** — the directory was and is empty |
| **Documents** | none existed before; my run created `default/profile.json`, which I **deleted**. No pre-existing document was altered |
| **Active selection** (`active`) | **overwritten, and NOT recovered.** My run replaced its contents; I deleted the file so the relay reselects on next boot. Its prior content is unrecoverable — not in version control, no backup |

So: keys, bundles and documents are intact; the **active screen selection is
lost**. The practical effect is that the next relay start against that store
picks a screen rather than restoring the previously selected one.

What my run left in `active` is kept as
[`real-store-damage-active.txt`](real-store-damage-active.txt). No further
cleanup or guessed restoration has been attempted, and none should be.

Every later run used `-Duser.home=<disposable>` and verified the real store's
checksum afterwards. That is now enforced rather than remembered:
`scripts/keliver-test-isolation-guard.sh` refuses to launch a test relay unless
the JVM's effective `user.home` **and** the resolved store are inside the
disposable root, and it explicitly rejects any path under the real
`~/.keliver-portal`.

## Files

[`repro-contamination.sh`](repro-contamination.sh) — the two-app regression;
`regression-before-fix.log`, `regression-after-fix.log`,
`store-conflict-refusal.log`, `real-store-damage-active.txt`.

Unit coverage in-repo: `PortalStoreOwnershipTest` (5) and `GuideTest` (5).
