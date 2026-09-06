# Smallest adopter route to a real device (2026-09-06)

An adopter's own feature, with its own presenter, running on an Android
emulator — **reusing the stock `portal-device-android` host unchanged**. No new
Android module, no new platform architecture.

## Acceptance criteria

| criterion | result |
|---|---|
| started outside the Keliver checkout | ✅ `scratchpad/adopter-device/newsstand`, scaffolded by `keliver-init` |
| released dependencies, `mavenLocal` disabled | ✅ every run `env -u KELIVER_USE_MAVEN_LOCAL`; deps pinned `0.3.3` from Central |
| the actual feed screen with its actual presenter | ✅ `FeedScreen(FeedPresenter(FeedRepository(http)))` compiled into the guest bundle |
| loading / populated / empty / error / Retry | ✅ see below |
| shown on an Android emulator | ✅ four screenshots in this directory |
| **not** the dogfood app or an overlay with preview mocks | ✅ see *Identity* |

## Observed on device

| state | on-screen text |
|---|---|
| populated | `Newsstand` · `3 articles` · `Fixture: Tide tables` / `Kelp survey` / `Gull counts` |
| empty | `Newsstand` · `No articles yet` |
| error | `Newsstand` · `Couldn't load articles` · `Retry` |
| **retry** | fixture flipped to 200 while the error screen was live, then `adb shell input tap 107 261` on the rendered button → `Newsstand` · `2 articles` · two rows |

Retry was a **real tap on the real button**, not an API call: the app was
sitting on the error screen, the fixture was changed underneath it, and only
the tap caused recovery.

Loading is transient here — the fixture replies immediately, so it is not
separately captured on device. It is asserted directly in the browser tests
(`FeedScreenBehaviourTest.loadingThenPopulated`) with a gated provider.

## Identity — why this is not the overlay or the dogfood app

- On-screen strings are this app's (`Newsstand`, `Fixture: …`). The dogfood app
  renders `Field Notes`.
- No `{statusLine}` placeholder anywhere. Preview mocks render bound props as
  `{field}`; these are resolved values from the presenter.
- `codeLoadSuccess modules=42`, and the served manifest contains a
  `newsstand` module — the host loaded **this app's** bundle.
- The states changed only when the app's own fixture file changed, and Retry
  only worked by tapping the button the app's own screen drew.

## The route (what an adopter has to do)

Scaffold, then four additions:

1. `settings.gradle` — `id 'app.cash.zipline' version '1.22.0'` in `pluginManagement`
2. `build.gradle` — apply `app.cash.zipline`; give the js target
   `binaries.executable()` (required for `serveDevelopmentZipline`)
3. dependencies — `keliver-treehouse-guest`, `keliver-treehouse-guest-compose`,
   `keliver-material-protocol-guest-web`, `keliver-http`, `app.cash.zipline:zipline`
4. a ~40-line `device/Main.kt` that takes the host's `HostHttpProvider` from
   `"HostHttp"` and binds a `PortalPresenter` rendering the app's screen

Then `./gradlew serveDevelopmentZipline` (serves :8080), run the relay for
fixtures, and launch the already-published `portal-device-android`.

**`PortalPresenter` is declared by shape in the app.** Zipline binds services by
name and signature, so the adopter does not need the host's module — which
matters because `portal-device-guest` is **not published** (404 on Central).

## The precise missing seam

Everything above is mechanical and identical for every adopter, yet none of it
is scaffolded. The smallest fix is a `keliver-new-device-target` scaffolder (or
a `--device` flag on `keliver-init`) emitting exactly those four additions.

Two smaller gaps found on the way:

- **`portal-device-guest` is unpublished**, so the `PortalPresenter` contract
  must be hand-copied. Publishing that one interface would remove the
  shape-duplication.
- **`ziplineApplication(name)` is a `redwoodBuild` helper**, unavailable to
  adopters. It turns out to be unnecessary for the dev-serve path (it only
  embeds a bundle into an APK), but that is not obvious and cost a build cycle.

Not implemented here: this is scaffolding work, and the assignment scopes out
turning it into a general-purpose project generator. The route is proven and
documented; the generator is the follow-up.

## Fixtures

`app/fixture-field-researcher.json` — deterministic, app-owned. Titles are
prefixed `Fixture:` so no screenshot can be mistaken for live data. The device
host routes all guest HTTP through the relay's `/http-replay`.

Note for fixture authors: `KeliverHttp.get()` sends **no headers** by default,
so a fixture with `matchHeaders: ["accept"]` never matches. Use `[]`.
