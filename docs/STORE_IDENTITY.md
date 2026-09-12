# The store ownership contract

Status: implementation note for the U23 / U24 / U25.1 correction. Written
before the fix, from the reproductions in
`scripts/keliver-store-identity-repro.sh`.

An app's **store** holds its signing identity (`keys/ed25519.{priv,pub}`), its
screen documents, and its published bundles. Everything downstream — a bundle
that verifies on a device, a production host that embeds the right public key —
depends on one question having one stable answer: *which store is this app's?*

Today that answer is a pure function of the app's path. Paths move, so the
identity moves with them, silently. This note fixes the answer.

## 1. What identity is, and is not

**An app's identity is the store it is bound to.** Not its directory name, not
its Git remote, not its path.

Two derivations are deliberately rejected:

* **Directory name.** `~/work/app` and `~/archive/app` are different apps that
  would collide.
* **Git remote.** Two clones of one repository are two independent working
  copies on one machine, and a fork or a rename changes the remote without
  changing the app. A remote is also attacker-supplied text in a repo you were
  handed.

A store is therefore bound to an app by a **mutual, two-sided marker**:

| side | file | committed? | written by |
| --- | --- | --- | --- |
| store → app | `<store>/owner` — the app's canonical path at claim time | n/a | `claimStoreFor`, atomically, once |
| app → store | `<app>/.gradle/keliver-store-path` — the store's absolute path | no, `.gradle` is gitignored | the relay, after a successful claim |

Neither side alone is proof. The pointer is machine-local and uncommitted, so a
`git clone` does not carry it — but `cp -a` does. The owner marker names a path,
and paths move. The contract below is what the two of them mean *together*.

## 2. Resolution order

Every consumer resolves in exactly this order. There is one authoritative
implementation, `PortalConfig.storeDir()`; `scripts/keliver-store-path.sh` is
its mirror, asserted equal by `StoreContractTest`; every other consumer calls
one of those two and none derives the path itself.

1. `PORTAL_STORE` — explicit, one run.
2. `store` in `<app>/keliver.portal.json` — explicit, committed.
3. `<app>/.gradle/keliver-store-path` — the binding pointer.
4. `~/.keliver-portal/apps/<slug>-<hash>` — the first-boot default.

**Step 4 in full.** `<hash>` is the first 8 hex digits of SHA-256 over the
*canonical* (symlink-resolved) absolute path. `<slug>` is the canonical
basename, lowercased, with every character outside `[a-z0-9._-]` replaced by
`-`, runs of `-` collapsed, and leading/trailing `-` trimmed.

Both halves derive from the **canonical** path. Before this change the hash did
and the slug did not, which is U25.1: `~/work/current -> ~/work/app-v2` resolved
`apps/current-<h>` or `apps/app-v2-<h>` depending on which path you typed — one
app, two stores, two signing identities, no warning. Collapsing `-` runs also
removes a Kotlin/Python divergence: Kotlin's regex maps each UTF-16 code *unit*
and Python maps each code *point*, so a name containing an astral character
produced `--` in one and `-` in the other.

**Step 4, compatibility scan.** The hash was already canonical, so an app's
existing 0.3.4 store directory differs from the new name only in the slug. If
`apps/<slug>-<hash>` does not exist, the resolver looks for existing
`apps/*-<hash>` directories:

* none — use `apps/<slug>-<hash>`; this is a first boot and a new identity;
* exactly one — use it, and say so. The identity, documents and bundles an
  existing app already has are what matter, not the spelling of the directory;
* more than one — **refuse**. That is a split identity (U25.1 already happened
  here), and choosing one silently is the failure being fixed. The message lists
  the candidates with their public-key fingerprints and points at recovery.

## 3. The five questions

### The same app, through its real path or through a symlink

One store. Steps 3 and 4 both go through the symlink to the same answer: the
pointer file is the same file either way, and the default canonicalises both
halves of the name.

### The app directory is renamed or moved

The pointer moves with the tree, so step 3 still resolves the original store —
its identity, documents and bundles. But `<store>/owner` records the *old* path,
and this app's canonical path is now the new one, so the claim mismatches.

**The relay refuses to start**, naming the store, both paths, and the recovery
command. It does not mint a new identity, and it does not rewrite the marker on
its own.

It refuses rather than adopting because the pointer cannot distinguish a move
from a copy: `cp -a` duplicates it. A rename is thus loud and one command away,
instead of silent and unrecoverable-looking. This is the U23 fix.

### The app is copied, or independently cloned

* **Copied** (`cp -a`) — the copy carries the pointer, so it resolves the
  original's store, mismatches the owner, and is refused. The instruction for
  the copy is *delete the pointer*, after which it takes a store of its own at
  step 4 (its canonical path differs, so the hash does). The original is
  untouched.
* **Cloned from Git** — `.gradle` is not committed, so there is no pointer. The
  clone takes a fresh store at step 4 and a fresh identity. Two clones on one
  machine are two apps.

An explicit committed `"store"` (step 2) is the one way a copy can reach the
original's store, and it is still refused by the owner marker. Copying
configuration must never confer ownership.

### The old owner path is unavailable

Nothing changes. The store stays owned by the path recorded in its marker, and
nothing is reclaimed automatically.

The bug register's suggested fix — treat a claim as reclaimable when the
recorded owner path no longer exists — is **not implemented**. Absence is not
proof of ownership: an unmounted volume, a deleted-and-recreated directory, or
any app that happens to sit at an unused path would all pass it, and the prize
is a private signing key.

What authorizes recovery is an operator running the recovery command and naming
both sides. The validations in it exist to catch mistakes, not to establish
ownership.

### Two apps attempt to use one store concurrently

`claimStoreFor` creates `owner` with `CREATE_NEW`, so exactly one first claimant
wins and every other is refused without altering anything
(`StoreClaimRaceTest`). Recovery serializes through the app lock and a store
lock — see [Recovery](#4-recovery) — so two concurrent recoveries have one
winner and the loser leaves both stores exactly as it found them, whether they
aim at the same store or at different ones.

## 4. Recovery

`keliver-store-recover.sh <app-dir> [--store DIR]` is the supported operation.
It rebinds an existing store to an app **without touching keys, documents or
bundles** — no hand-editing of `owner`, which is what U24 forced.

### It is one two-sided update, and success means the binding works

`owner` and the pointer are two writes. The command:

1. checks the pointer's destination is usable **before** anything is mutated —
   a probe write, not just `-w`, because the failure modes that matter are
   "`.gradle` is a regular file" and "the pointer path is a directory";
2. takes the app lock (below), then validates;
3. writes `owner`, then the pointer, remembering the previous value of each;
4. **runs the ordinary resolver** and requires it to select the intended store,
   and `owner` to name this app;
5. on any failure at 3 or 4, restores both sides and exits non-zero.

An exit status of 0 therefore means the resolver agrees, not that two `printf`s
were attempted. The first version of this command exited 0 having rewritten
`owner` while the pointer write failed — leaving a store owned by an app that
did not resolve to it.

**The backup is files, not shell variables.** `$(cat f)` strips trailing
newlines, so a marker restored from a variable is not necessarily the marker
that was there; and whether the pointer *existed* is state of its own. Both
sides are copied into `<app>/.gradle/keliver-store-recover.backup.<pid>/`
(`owner`, `pointer`, `pointer.existed`, `meta`), every copy is verified with
`cmp`, and restoration is verified the same way **before** it is described as
having happened.

**If restoration itself fails**, the backup is kept, the command prints the
state as it actually is — both files, as they are on disk now — with the exact
`cp` commands to put them back, and exits non-zero. It never says "unchanged"
about a store it has changed.

### Interruption

| when | what happens |
| --- | --- |
| before either write | nothing had been written; the locks are released and it exits non-zero |
| between the two writes, or before verification | the previous owner marker and pointer are restored **while the locks are still held**, the restoration is verified, then the locks are released and it exits non-zero |
| after the transaction is verified | the rebinding stands; a late signal does not undo verified work |

`SIGINT` and `SIGTERM` are handled explicitly, and the handler exits — it does
not return. A trap that only released the locks was *worse than none*: bash
resumes at the interrupted statement once a handler returns, so a `TERM`
between the two writes released both locks and then went on to finish the
update and report success. Further signals are ignored while unwinding, and
every mutation re-checks an "aborting" flag, so nothing can run after cleanup
has begun.

Cleanup is idempotent, and it releases **only locks this process still holds**,
identified by the pid marker it wrote. Without that check an interrupted run
could remove a lock a *later* process had already taken over — the same
two-writers failure the lock exists to prevent.

**`SIGKILL` and power loss cannot be rolled back, and nothing here claims
otherwise.** There is no handler for them, so the store can be left owned by
the app with the pointer not yet written, or the reverse. What survives is the
backup directory, which is deleted only once the transaction is verified: a
later run reports any it finds and never overwrites one, and the lock's pid
then belongs to a dead process, so the next contender takes the lock over.

### It respects precedence, and does not invent it

Validation asks the resolver *what this app selects today and by which rule*
(`keliver-store-path.sh --explain`), rather than re-deriving the order in a
second place. The answer decides:

| the app resolves by | outcome |
| --- | --- |
| `config` — `"store"` in `keliver.portal.json` | **refused.** A committed setting outranks this command; recovering anything else would leave the app resolving elsewhere. Edit the file instead. |
| `pointer` or `default`, to a store holding an identity or documents | **refused.** Adopting would abandon that identity, and two stores are never merged. |
| `pointer` or `default`, to nothing that exists yet | allowed; the pointer is this command's to rewrite. |
| a **refusal** (a split) | allowed only when `--store` names one of the split candidates. Any other refusal is reported, not swallowed. |

A resolver refusal is never read as "this app has no store". That reading is
what let a recovery proceed against an app that was already bound elsewhere.

**`PORTAL_STORE` is ignored, loudly.** It is a one-run override, and a
permanent binding must not be decided by a variable that will be gone by the
next command. Every resolver call the recovery makes — including the final
verification — runs with it unset, and a set `PORTAL_STORE` is reported on
stderr so the result is never silently about a different store than the one the
next ordinary run will select.

### The same-owner split

`claimStoreFor` records the **canonical** path, so after a symlink split both
stores name the same app. `owner == me` is therefore not evidence that the
app-side binding exists, or that it selects this store — and the first version
of this command treated it as "already bound; nothing to do" and exited 0,
leaving the split in place and the relay still refusing.

When `--store` names one of the split candidates the recovery proceeds,
establishes the pointer (the half that was missing), and verifies. The
unselected store is not read, written or moved; its identity is untouched and
remains available to `--store` later.

### It refuses, changing nothing, when

* the store has no `owner` marker — there is nothing to recover; just start;
* the recorded owner path still exists **and** still resolves to this same
  store — that is two live apps, not a relocation;
* precedence forbids it, per the table above;
* the pointer destination is unusable;
* another recovery, or a starting relay, holds the app lock.

On success it prints the store's public-key fingerprint before and after
(identical, by construction — nothing under `keys/` is read, written or moved)
and the resolver's verified answer.

### The lock is the app's, and the relay takes it too

`<app>/.gradle/keliver-store.lock`, created with `mkdir` so the shell and the
JVM hold the same lock.

It is the **app's** lock rather than the store's for two reasons. Two
recoveries aiming at two *different* stores for one app never contend for a
per-store lock, so a per-store lock cannot stop them producing an `owner` on
one store and a pointer to the other. And the relay performs this same
two-sided update at startup — it claims the store and writes the pointer — so a
lock only recovery commands took would not serialize the writer most likely to
be running.

The relay waits up to 20 seconds for a recovery in flight and then refuses to
start, naming the lock. If the lock directory cannot be created at all (a
read-only app tree) the relay says so and starts unlocked, because refusing to
start there would be the worse outcome. A store lock (`<store>/owner.lock`) is
still taken inside the app lock, so two apps recovering the same store also
serialize.

**A lock must not outlive its holder.** Introducing a lock that blocks startup
introduces a way to wedge the portal, so the holder records its pid inside the
lock directory and a later claimant takes the lock over when that process is
gone. An *unreadable* pid means wait, not steal: the cost of waiting is a
message, the cost of stealing is two writers.

The takeover is **claimed, not just performed**. Seeing a dead pid and then
deleting the directory can delete a lock a different contender acquired in
between. Instead the `pid` file is renamed aside — exactly one contender can do
that — and the claim is then checked to still hold the dead pid that was
inspected; if it does not, it is put back and the contender waits. A live holder
always writes its `pid` before doing anything, and a new holder can only exist
after this same rename removed the old marker, so a successful content-checked
claim proves this is not somebody else's live lock. `keliver-store-recover.sh`
and `PortalConfig.withStoreLock` implement the same protocol, so the shell and
the JVM contend correctly with each other. Pid reuse can still defeat the
liveness test; the bounded wait and a refusal naming the directory are the
backstop.

Startup resolves the store **inside** the lock. It used to resolve first and
lock afterwards, so a start that waited on a recovery in flight went on to
claim the store it had selected *before* that recovery ran — and then wrote
that stale answer back into the pointer, undoing the rebinding it had just
waited for. Selecting, claiming and recording the binding are one transaction.

**Known limitation, unchanged:** the lock covers relay *startup*. A relay that
is already running does not hold it, and although it does not rewrite the
pointer after startup, a recovery performed underneath a live relay leaves that
process serving a store it no longer owns. Stop the portal before recovering.
Recovery has deliberately **not** been broadened to support running underneath
an active relay.

## 5. Existing 0.3.4 stores

* **An app that has booted at least once and has not moved.** Nothing changes:
  its pointer resolves the same store and the owner still matches.
* **An app with no pointer** (never booted, or `.gradle` removed) at an
  unchanged path. The hash is identical, so the compatibility scan adopts the
  existing directory even where the new slug rule spells the name differently.
* **An app that was already split across a symlink and a real path.** Two
  stores end in the same hash, so the resolver refuses and names both with
  their fingerprints. This is the one case where an app that used to start now
  does not — deliberately: it had two identities and picking one silently is the
  defect.
* **An app that was renamed under 0.3.4 and has already minted a second
  identity.** It starts normally: its pointer names the *new* store and that
  store's owner is the new path. To go back to the original identity, move the
  new store aside and then name the old one:

  ```bash
  mv ~/.keliver-portal/apps/<new>-<hash> ~/.keliver-portal/apps/<new>-<hash>.abandoned
  keliver-store-recover.sh /abs/path/to/app --store ~/.keliver-portal/apps/<old>-<oldhash>
  ```

  Recovery refuses while the new store still holds an identity, because
  adopting over it would abandon a signing key without saying so.
* **A pointer naming a directory that no longer exists.** It is created and
  claimed as a first boot. There is nothing left to preserve, and the
  alternative — refusing — would strand an app whose store was deliberately
  deleted.

No migration step is required, and nothing rewrites an existing store.

## 6. What this does not change

* Cross-app isolation. An unrelated app still cannot claim a foreign store; the
  only new path to a store is the explicit recovery command, which refuses while
  the old owner is live.
* Existing signing identities. No key is generated, rotated, read or moved by
  any part of this change.
* The `"store"` escape hatch, `PORTAL_STORE`, or the legacy-store adoption
  route (`keliver-adopt-legacy-store.sh`), which copies and is unrelated.
* U25.2 (`HostTrustPolicy.HEX` length), U25.3 (`devOnlyHost` parsing) and U25.4
  (`keliverStoreDir` warn-and-fall-back) stay open. They are not store identity.
