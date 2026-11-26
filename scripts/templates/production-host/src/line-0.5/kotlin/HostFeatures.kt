/*
 * The host features this host implements, on the 0.5 line: the one place a host template declares
 * them (NF5 H0, docs/SHOW_BEFORE_COMPUTE.md I8). The scaffolders (keliver-new-production-host.sh,
 * keliver-new-ios-host.sh) copy the file of the app's line (appRuntime.keliverVersion,
 * keliver-runtime-line.sh); the Android and iOS hosts ship the same file.
 */
package @@PACKAGE@@

/**
 * What this host implements of the optional host features, by name: the set its Treehouse hands
 * every guest (KeliverLine.kt's KeliverSpec; the guest reads it as
 * `dev.keliver.compose.LocalHostFeatures` and uses a feature only where its host declares it, D2),
 * and the one set whatever else in the host asks what it implements reads.
 *
 * Declare a feature only when this host implements all of it: one line per feature, with the name
 * Keliver gives it (`dev.keliver.compose.KeliverHostFeatures`), or a name outside `keliver.` for a
 * feature of the app's own. Keliver's build holds every `keliver.` name here to Keliver's list and
 * to what each feature requires (portal-relay's HostFeaturesTemplateTest).
 */
internal object HostFeatures {
  val implemented: Set<String> = setOf(
  )
}
