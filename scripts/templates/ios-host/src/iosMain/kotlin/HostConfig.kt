package @@PACKAGE@@

// Written by keliver-new-ios-host.sh. Commit this file.
//
// PORTAL_PUBLIC_KEY_HEX is this app's portal PUBLIC key (keys/ed25519.pub in
// its store): every bundle's manifest must verify against it. The private key
// is never here. Keep each line in the form `const val NAME: String = "..."`;
// the build checks them (build.gradle: checkHostConfig, checkReleaseUrls).
//
// PORTAL_NEXT_PUBLIC_KEY_HEX (W8) is empty, except while a signing key is being
// rotated: then it is the NEXT key's public half, and the host trusts bundles
// signed by either key (docs/KEY_ROTATION.md).
//
// CHANNEL is the release channel this host takes from bundles/index.json,
// besides stable (W4.4): e.g. beta for testers. Lower-case letters, digits and '-'.
//
// BUNDLE_SERVER is where bundles come from. A DEBUG build also takes it from
// the KELIVER_BUNDLE_SERVER launch environment variable, when set (a tunnel's
// new URL without a rebuild); a release build uses only this line.
//
// UPDATES is when the running host looks for a newer bundle (W5): "next-launch"
// (only when the process starts), "on-resume" (also each time the app returns
// to the foreground, at most every 30 s, applying a newer bundle at once) or
// "live" (a DEBUG build only: on-resume, and while a Keliver screen is in the
// window the host also listens to the bundle server's /bundles/events, so a
// publish reaches the open app; a server without it, a static one, leaves it
// on-resume; in a release build "live" is "on-resume").
// Empty: "live" in a DEBUG build and "next-launch" in a release one. A value
// here applies to every build.
//
// REPORT_URL (W6, optional): where the host POSTs a small JSON report of each
// outcome (loaded, fell back, update applied or failed, not loaded): your
// collector. Empty: no reports leave the device (Keliver.onReport still gets
// them). A release build refuses http://.
internal const val PORTAL_PUBLIC_KEY_HEX: String = "@@KEY_HEX@@"
internal const val PORTAL_NEXT_PUBLIC_KEY_HEX: String = ""
internal const val BUNDLE_SERVER: String = "@@BUNDLE_SERVER@@"
internal const val API_BASE_URL: String = "@@API_BASE_URL@@"
internal const val CHANNEL: String = "@@CHANNEL@@"
internal const val UPDATES: String = ""
internal const val REPORT_URL: String = ""
