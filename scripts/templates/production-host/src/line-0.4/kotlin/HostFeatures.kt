/*
 * The host features this host implements, on the 0.4 line (NF5 H0, docs/SHOW_BEFORE_COMPUTE.md
 * I8). The scaffolders (keliver-new-production-host.sh, keliver-new-ios-host.sh) copy the file of
 * the app's line (appRuntime.keliverVersion, keliver-runtime-line.sh); the Android and iOS hosts
 * ship the same file.
 */
package @@PACKAGE@@

/**
 * What this host implements of Keliver's optional host features, by name: on the 0.4 line, none.
 * Its Treehouse hands a guest no host features (`dev.keliver.compose.LocalHostFeatures` is empty),
 * so whatever in the host asks what it implements sees what its guests see. The 0.5 line's copy of
 * this file is where a host declares features; nothing declared there changes this one.
 */
internal object HostFeatures {
  val implemented: Set<String> = emptySet()
}
