# W3 on iOS: hosted CI, `ios-host.yml` run 37629943934 (PR #90, 2026-10-07)

macos-15 runner, an iOS simulator. Run 37639110263, at `8c4f5e5f`, repeated
it with the same counts (49/0, 48/0). The CI runs after the independent
review's fixes are listed in PR #90 and in the plan's Status.
 The host is scaffolded by this branch's
`keliver-new-ios-host.sh`; the app comes from the public tools 0.3.6 release.

- `selftest.txt`: `keliver-new-ios-host-selftest.sh --build`, **49/0**. It includes the
  index lookup check, and `xcodebuild` links the shared `BundleIndex.kt`.
- `ios.results`: **48/0**.
  - **P-rows (relay route):** the 0.3.6 relay serves no index, so the host's
    fallback to `/bundles/latest` on the 404 is asserted (`P2-v1.KeliverHost.txt`).
  - **W3 rows:**
    - `keliver-publish` (built from this checkout) writes into a directory
      served by `ci/w3/static_https.py` over HTTPS. That server's throwaway CA
      is added only to this run's simulator (`w3-ca.pem`).
    - **S2:** v1 loads through `bundles/index.json`; the screen reads "Depot"
      (`S2-static.*`, `w3-server.log`).
    - **S4:** v2, after a source edit and a second CLI publish, reads
      "Warehouse".
    - **S5:** checked against another app's public key, the CLI refuses this
      app's already-built bundle (`--skip-build`) with exit 4, and the site is
      byte-identical (`w3-publish-foreign-key.log`).
    - **S6:** with a wrong `manifestSha256` in the index, the host refuses the
      manifest (`S6-pin.KeliverHost.txt`). Nothing loads, even though a
      verified v2 is cached: Zipline has no fallback to the cache after a
      network failure (W5).
    - **S7:** with the server down, the host starts from its cached v2.
- `w3-index-v1.json`, `w3-index-v2.json`: the index exactly as the CLI wrote it.

Here the app's signing block is the one tools 0.3.6 wrote, so the CLI signs
through the app's (disposable) store. The Android run uses
`KELIVER_SIGNING_KEY_FILE`.
