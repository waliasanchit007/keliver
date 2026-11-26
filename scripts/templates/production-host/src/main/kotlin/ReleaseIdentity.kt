/*
 * The identity of a release as the app that ran it saw it (NF5 H0, docs/SHOW_BEFORE_COMPUTE.md
 * 2.8). Scaffolded by keliver-new-production-host.sh and keliver-new-ios-host.sh; the Android and
 * iOS hosts ship the same file.
 */
package @@PACKAGE@@

import app.cash.zipline.ZiplineManifest
import okio.Buffer
import okio.ByteString

/**
 * What a code load that succeeded loaded, from its own verified manifest (`codeLoadSuccess`), never
 * from what preferences say was loaded: its signed [sequence] (null: the manifest names none) and
 * [moduleDigest] ([moduleDigest] of its modules). With the sha256 of the manifest bytes it is the
 * release that the last good bundle's record names, written before the load is reported.
 */
internal class LoadedRelease(
  val sequence: Long?,
  val moduleDigest: String,
)

internal fun loadedRelease(manifest: ZiplineManifest): LoadedRelease =
  LoadedRelease(manifestSequence(manifest.metadata), moduleDigest(manifest.modules.mapValues { it.value.sha256 }))

/**
 * One sha256 (hex) over a release's modules, each as its id and the sha256 of its bytes, in id
 * order: the same modules give the same digest whatever order a manifest lists them in, and a
 * change to any module's bytes, a module added or one removed gives another.
 */
internal fun moduleDigest(modules: Map<String, ByteString>): String {
  val buffer = Buffer()
  for ((id, sha256) in modules.entries.sortedBy { it.key }) {
    buffer.writeUtf8(id).writeByte('\n'.code).writeUtf8(sha256.hex()).writeByte('\n'.code)
  }
  return buffer.sha256().hex()
}
