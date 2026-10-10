# W3 on Android: hosted CI, `reference-app.yml` run 37629943998 (PR #90, 2026-10-07)

ubuntu-latest; emulator API 33 x86_64 (aosp_atd). Run 37639110172, at
`8c4f5e5f`, repeated it with the same counts. The CI runs after the
independent review's fixes are listed in PR #90 and in the plan's Status.
 The app comes from the
public tools 0.3.5 release. The production host is scaffolded by this branch's
`keliver-new-production-host.sh`.

- `prepare.results`: **18/0**. It builds `keliver-publish` from this checkout
  and a throwaway CA, and builds the same host for `https://10.0.2.2:8443`.
- `keliver-publish-selftest.txt`: **16/0** (`scripts/keliver-publish-selftest.sh`).
- `device.results`: **54/0**.
  - **P2:** the 0.3.5 relay serves no index, so the host falls back to
    `/bundles/latest` (`logcat-prod-v1.KeliverHost.txt`).
  - **W3 rows:**
    - The CA is put in the emulator's system store, on a tmpfs, until reboot
      (`w3-android-ca.log`).
    - `keliver-publish` signs with `KELIVER_SIGNING_KEY_FILE`, and
      `KELIVER_TOOLS_BIN` points at nothing, so a store lookup would have
      failed the build (`w3-publish-v1.log`).
    - **S2:** v1 through `bundles/index.json` over HTTPS shows "Depot"
      (`S2static.results.json`, `w3-server.log`).
    - **S4:** v2 shows "Warehouse".
    - **S5:** checked against another app's public key, the CLI refuses this
      app's already-built bundle (`--skip-build`), and the site is
      byte-identical.
    - **S6:** a wrong `manifestSha256` makes the host refuse the manifest
      (`logcat-static-pin.KeliverHost.txt`). Nothing loads, even though a
      verified v2 is cached: Zipline doesn't fall back to the cache after a
      network load fails (W5 plans that fallback).
    - **S7:** with the server down, the host starts from its cached v2.
