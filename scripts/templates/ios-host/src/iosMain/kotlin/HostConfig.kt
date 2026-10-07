package @@PACKAGE@@

// Written by keliver-new-ios-host.sh. Commit this file.
//
// PORTAL_PUBLIC_KEY_HEX is this app's portal PUBLIC key (keys/ed25519.pub in
// its store): every bundle's manifest must verify against it. The private key
// is never here. Keep each line in the form `const val NAME: String = "..."`;
// the build checks them (build.gradle: checkHostConfig, checkReleaseUrls).
internal const val PORTAL_PUBLIC_KEY_HEX: String = "@@KEY_HEX@@"
internal const val BUNDLE_SERVER: String = "@@BUNDLE_SERVER@@"
internal const val API_BASE_URL: String = "@@API_BASE_URL@@"
