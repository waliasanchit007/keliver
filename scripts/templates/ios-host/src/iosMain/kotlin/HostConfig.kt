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
//
// CRASH_THRESHOLD (WS6, crash rollback): how many failures of one bundle on this
// install set it aside (its code failed to load, not a download; it threw; or
// the app ended in the foreground while it loaded or showed, before it proved
// healthy, a debugger's stop too); the host then goes back to the last bundle
// that proved healthy here, or to the app's own view. 0 turns crash rollback
// off: do that while you stop and restart a new bundle in Xcode. CRASH_WINDOW_MILLIS:
// how long screens show a new bundle, with no failure, before it proves healthy.
//
// START_POLICY is how the host starts (docs/DEVICE_HOST.md, "Start policy"):
// "network-first" (the lookup, then the bundle, before anything shows),
// "parallel" (the cached bundle loads while the lookup runs, and shows once the
// lookup confirms it; its calls out wait until then, and fail unsent when it is
// dropped) or "cache-first" (the cached bundle shows at once; a newer one the
// background lookup finds loads at the next start, or as an update with UPDATES
// "on-resume" or "live"). Empty: parallel, the default. A release owner's override in
// the index (keliver-publish --set-start-policy) takes its place from the next
// start, unless START_POLICY_OVERRIDE is "none" ("any", or empty: allowed): an
// app that must start network-first whatever the index says sets both.
internal const val CRASH_THRESHOLD: Int = 2
internal const val CRASH_WINDOW_MILLIS: Long = 10_000L
internal const val PORTAL_PUBLIC_KEY_HEX: String = "@@KEY_HEX@@"
internal const val PORTAL_NEXT_PUBLIC_KEY_HEX: String = ""
internal const val BUNDLE_SERVER: String = "@@BUNDLE_SERVER@@"
internal const val API_BASE_URL: String = "@@API_BASE_URL@@"
internal const val CHANNEL: String = "@@CHANNEL@@"
internal const val UPDATES: String = ""
internal const val REPORT_URL: String = ""
internal const val START_POLICY: String = ""
internal const val START_POLICY_OVERRIDE: String = ""
