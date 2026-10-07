# The production iOS host in CI — run 37597390396 (2026-10-07)

[`ios-host.yml` run 37597390396](https://github.com/waliasanchit007/keliver/actions/runs/37597390396)
at `1eb80a9f4` on PR #88: a hosted `macos-15` runner, **Xcode 16.4**, an
**iPhone 17 Pro simulator on iOS 26.2**, created by the run
(`simulator.txt`).

| step | result |
|---|---|
| the **public** tools 0.3.6 download | `keliver-portal-tools-0.3.6.zip: OK` against the release's own `.sha256`; the pinned hash `fac98912…` and `sourceCommit d52e2eca` match (`public-download.txt`). This is also the 0.3.6 release's post-publish check from a GitHub-hosted runner. |
| `keliver-new-ios-host-selftest.sh --build` | **43/0** (`selftest.txt`): refusals byte-identical, output complete, `xcodebuild` for the simulator, release refuses `http://` on both links, a malformed key fails the build |
| `reference/inventory/ci/ios.sh` | **30/0** (`ios.results`) |

The iOS P-checks (screens read by macOS Vision; `*.ocr.txt`, screenshots kept):
- **P1:** `host-ios` scaffolded by this repository's
  `keliver-new-ios-host.sh`. It embeds this app's key (`app-public-key.hex`),
  built from Maven Central (54 `dev.keliver` artifacts, all 0.3.3), and is
  installed as `inventory.ioshost`.
- **P2:** signed v1 loads, the screen reads **Inventory**, and there is no
  failed or empty-URL load.
- **P4:** an `/ops` title edit, published as v2, is loaded; the screen reads
  **Stockroom**.
- **P5:** a copy of the app, with its own store and key, publishes a bundle,
  and the host refuses it on its signature. Nothing loads, and "Foreign
  build" never appears.
- **P6:** this app's relay is back and its bundle loads again (Stockroom).
- **P7:** with the relay down, `lookup failed; starting from the cached
  bundle`, `codeLoadSuccess`, and **Stockroom** offline.

P3 (repeated taps) has no iOS input driver here and was not run. The relay
from the released 0.3.6 bundle wrote `ed25519.priv` as `-rw-------` on this
runner too (`key-modes.txt`).

Kept here: results, the key, the dependency list, the manifests, each
launch's `KeliverHost:` console lines and OCR reading, and five screenshots.

**After this run,** the independent review of PR #88 found nothing blocking.
Its points are fixed in the next commit:
- one URL grammar (no `$`, no `user@`, no query);
- the release build refuses ATS exceptions;
- HostHttp checks the method and headers;
- the cleanup trap is installed first;
- the OCR result is checked;
- the P5 label is honest.

The later CI run records them.
Everything else, including full logs and the xcodebuild log, is in the run's
artifact while it lasts.
