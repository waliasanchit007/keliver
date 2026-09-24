# Reference-app evidence

What `docs/REFERENCE_APP.md` cites, kept in the repository because CI artifacts
expire after 30 days.

* `ci-run-35802020305/` — a durable subset of run
  [`35802020305`](https://github.com/waliasanchit007/keliver/actions/runs/35802020305)'s
  artifact: every results file, the public-download check, the D14 diff and
  `logic/` hashes, the published manifests' hashes, the production host's APK
  hash and source commit, this run's disposable **public** key, and the
  `PortalDevice` lines of each logcat. Not copied: the APK itself, full logcats
  and view dumps — they are in the run's artifact while it lasts. No harness
  script reads, prints or copies a private key; the only thing that reads one is
  the Zipline compile task's signing block (`app/build.gradle`), on each publish.
  The keys were generated inside the run directory and discarded with the runner,
  and every 64-hex-digit string here is a SHA-256 or a public key.
* `ci-run-35967437120/` — from run
  [`35967437120`](https://github.com/waliasanchit007/keliver/actions/runs/35967437120),
  after the review's corrections: the results files (device 32/0), the published
  manifests themselves (public: module hashes and signatures), `key-modes.txt` —
  the store's key modes on the Linux runner, `-rw-r--r--`, the U27 measurement
  from the published 0.3.5 relay — and `shots.results`, the verdict on each
  device screenshot: all six BLANK. The black PNGs themselves are in the run's
  artifact only.
* `live-preview/` — the macOS Live-preview run: the CDP scenario
  (`live-scenario.mjs`, `cdp.mjs`), its log and results (21/0), the script's
  four captures with ▶ Live on (`L-*.png`), and one mock-mode capture taken by
  hand (`item-mock.png`). All five are browser captures of the editor canvas, not
  device renders.

`P5.results.json` reports two failed checks by design: they probe whether the
foreign-signed bundle's title reached the screen, and it must not have.
