# W1 I0 — an iOS production host from published artifacts (2026-10-07)

**Question:** can an adopter's iOS production host compile and link with
Keliver resolved only from Maven Central, as `#84` asked for Android?

**Answer, measured on macOS (Xcode 26.4.1, Kotlin 2.2.0, Compose 1.8.2, Zipline 1.22.0):
yes.** `linkDebugFrameworkIosSimulatorArm64` succeeded (`link-result.txt`).
Every Keliver dependency came from Maven Central: 54 `dev.keliver` artifacts,
all 0.3.3, no project or `mavenLocal` dependency (`keliver-artifacts.txt`).
The framework exports `MainViewControllerKt.MainViewController()` to Swift
(`framework-header-excerpt.txt`). It was built with a disposable Gradle home,
`user.home` and `KONAN_DATA_DIR`, and a dummy public key.

The host compiled here (`proj/src/iosMain/kotlin/`) is a first **production**
host, not the spike in `portal-device-ios`. It has parity with the Android
scaffold:
- production-only: no key means no fetch, and never `NO_SIGNATURE_CHECKS`;
- the lookup runs first with a 10 s timeout;
- an offline start from Zipline's pinned cache (`AcceptCachedBundle`);
- one cache per key;
- manifest URLs only on the bundle server's origin;
- `HostHttp` over `NSURLSession` with the Android path and header rules, and
  redirects refused.

**Not shown here:** that it RUNS. No app was built, nothing was installed on a
simulator, and no bundle was loaded. That is I1 and I2. The SQL host is
still the spike's in-memory one, and images have no network fetcher. The
debug static framework is 339 MB.

Reproduce: `measure.sh <empty dir>`.
