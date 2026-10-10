package @@PACKAGE@@

// Written by keliver-new-ios-host.sh. Commit this file.
//
// PORTAL_PUBLIC_KEY_HEX is this app's portal PUBLIC key (keys/ed25519.pub in
// its store): every bundle's manifest must verify against it. The private key
// is never here. Keep each line in the form `const val NAME: String = "..."`;
// the build checks them (build.gradle: checkHostConfig, checkReleaseUrls).
//
// CHANNEL is the release channel this host takes from bundles/index.json,
// besides stable (W4.4): e.g. beta for testers. Lower-case letters, digits and '-'.
//
// UPDATES is when the running host looks for a newer bundle (W5): "next-launch"
// (only when the process starts) or "on-resume" (also each time the app returns
// to the foreground, at most every 30 s, applying a newer bundle at once).
//
// REPORT_URL (W6, optional): where the host POSTs a small JSON report of each
// outcome (loaded, fell back, update applied or failed, not loaded): your
// collector. Empty: no reports leave the device (Keliver.onReport still gets
// them). A release build refuses http://.
internal const val PORTAL_PUBLIC_KEY_HEX: String = "@@KEY_HEX@@"
internal const val BUNDLE_SERVER: String = "@@BUNDLE_SERVER@@"
internal const val API_BASE_URL: String = "@@API_BASE_URL@@"
internal const val CHANNEL: String = "@@CHANNEL@@"
internal const val UPDATES: String = "next-launch"
internal const val REPORT_URL: String = ""
