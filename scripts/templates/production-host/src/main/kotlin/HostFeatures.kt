/*
 * The host features this host implements: the one place a host template declares them (NF5 H0,
 * docs/SHOW_BEFORE_COMPUTE.md I8). Scaffolded by keliver-new-production-host.sh and
 * keliver-new-ios-host.sh; the Android and iOS hosts ship the same file.
 */
package @@PACKAGE@@

/**
 * What this host implements of Keliver's optional host features, by name. A guest reads the set
 * (`dev.keliver.compose.LocalHostFeatures`) and uses a feature only where its host declares it (D2):
 * an older host declares nothing and gets nothing it cannot draw.
 *
 * KeliverLine.kt hands it to Treehouse (`TreehouseApp.Spec.hostFeatures`) on Keliver lines that
 * have host features (0.5 on); on the 0.4 line the guest sees none, whatever is declared here.
 *
 * Declare a feature only when this host implements all of it: one line per feature, with the name
 * Keliver gives it (`dev.keliver.compose.KeliverHostFeatures`). HostFeaturesTest holds this set to
 * what the host implements; Keliver's own tests hold every name to that list and to what each
 * feature requires.
 */
internal object HostFeatures {
  val implemented: Set<String> = setOf(
  )
}
