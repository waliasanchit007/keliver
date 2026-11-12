/*
 * Every configuration but Debug (ED3): no debug link. Xcode's KELIVER_DEBUG_LINK is not YES here,
 * so build.gradle compiles this file instead of src/debuglink/: no link handler, no link check,
 * and no stored server is ever read. Scaffolded by keliver-new-ios-host.sh.
 */
package @@PACKAGE@@

/** No debug link in this build: always BUNDLE_SERVER (or, in a debug binary, the launch environment). */
@Suppress("NOTHING_TO_INLINE")
internal inline fun debugLinkBundleServer(): String? = null
