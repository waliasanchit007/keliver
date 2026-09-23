# Reference-app evidence

What `docs/REFERENCE_APP.md` cites, kept in the repository because CI artifacts
expire after 30 days.

* `ci-run-35802020305/` — a durable subset of run
  [`35802020305`](https://github.com/waliasanchit007/keliver/actions/runs/35802020305)'s
  artifact: every results file, the public-download check, the D14 diff and
  `logic/` hashes, the published manifests' hashes, the production host's APK
  hash and source commit, this run's disposable **public** key, and the
  `PortalDevice` lines of each logcat. Not copied: the APK itself, full logcats
  and view dumps — they are in the run's artifact while it lasts. No private key
  was ever read, printed or copied; the keys were generated inside the run
  directory and discarded with the runner.
* `live-preview/` — the macOS Live-preview run: the CDP scenario
  (`live-scenario.mjs`, `cdp.mjs`), its log and results (21/0), and five
  captures of the editor with ▶ Live on.

`P5.results.json` reports two failed checks by design: they probe whether the
foreign-signed bundle's title reached the screen, and it must not have.
