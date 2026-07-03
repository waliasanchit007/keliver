# Portal Phase 4: Publish Pipeline — Implementation Plan

> Executed autonomously (superpowers:executing-plans; user AFK, all-phases SOTA push).

**Goal:** "Publish" turns the active screen's tree into **compiled, EdDSA-signed, versioned `.zipline` Kotlin** served by portal-server with widget-protocol compat gating, and the Android host gains a **prod mode** that verifies signatures and renders the published bundle with no relay/interpreter — plus the designed failure path: a contract without a hand-written impl fails the publish with a readable log.

**Architecture (locked):**
- **Keys:** portal-server generates an Ed25519 keypair at boot if absent (`~/.keliver-portal/keys/ed25519.priv|.pub`, raw-32-byte hex via JDK 17 Ed25519, PKCS8/X.509 prefixes stripped). Private key never leaves the machine; guest build signs with it; host embeds the public key at build time (gradle copies it into assets; falls back to NO_SIGNATURE_CHECKS with a loud log when absent).
- **Template guest `portal-published-guest`** (new module, mirrors portal-device-guest): same `PortalPresenter`/`HostApi` service names so the HOST APP IS UNCHANGED except manifest URL + verifier. `Show()` renders `PublishedEntry()`. The publish step generates TWO files into its `src/jsMain/kotlin/generated/`: `PublishedScreen.kt` (exportKotlin output + package) and `PublishedEntry.kt` (`PublishedScreen()` or `PublishedScreen(PublishedBindings)` per contract). Engineers own `PublishedBindings` (an object implementing the generated interface) in `src/jsMain/kotlin/impl/` — missing impl = compile error = the round-trip boundary enforced by the pipeline.
- **Publish flow** (portal-server `POST /publish`): read active draft → exportKotlin → write generated files → `ProcessBuilder ./gradlew :portal-published-guest:compileDevelopmentZipline` (signing keys picked up from the key files via the module's build.gradle) → on success copy `build/zipline/Development` to `~/.keliver-portal/bundles/v<N>/` + `meta.json` (version, widgetVersion=1, treeHash, createdAt) → respond with the full gradle log either way.
- **Serving + gating:** `GET /bundles/latest?widgetVersion=W` → `{version,url}` of the newest bundle with recorded widgetVersion ≤ W; `GET /bundles/v<N>/<file>` static serving.
- **Prod device mode:** portal-device-android reads intent extra `mode=prod` → manifestUrl `http://10.0.2.2:8077/bundles/v<N>/manifest.zipline.json` (resolved via /bundles/latest at startup by the host) + `ManifestVerifier.Builder().addEd25519("portal", pubHex)`; dev mode unchanged.
- Deviation from spec noted: the headless QuickJS load smoke test before `latest` is deferred (the compile gate + signature verification cover P4's bar).

**Tasks**
1. Keys at server boot + `/publickey` (hex; host build convenience). Curl-verify.
2. `portal-published-guest` module + committed placeholder generated files + impl example; compiles standalone; zipline signing wired from key files (conditional).
3. portal-server: portal-core dep (exportKotlin/deserializeTree on JVM), `/publish` (ProcessBuilder + log capture), bundle store + `/bundles` endpoints. Verify: publish the demo screen → v1 signed bundle in store, manifest contains signature; failure path: temporarily remove impl → publish fails with readable log.
4. Editor: Publish button + log overlay.
5. Host prod mode (intent extra + embedded pubkey from assets) → emulator renders the PUBLISHED screen with relay STOPPED; tamper a bundle byte → codeLoadFailed (signature) in logcat = signing proof. Commit + memory.
