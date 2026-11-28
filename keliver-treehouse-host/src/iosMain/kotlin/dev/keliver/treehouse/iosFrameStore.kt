/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.keliver.treehouse

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np

/**
 * The [FrameStore] for an iOS app: frames live in `Library/Caches/keliver-frames`, marked excluded
 * from backup, with the default file protection (docs/SHOW_BEFORE_COMPUTE.md I5). The host build in
 * the key is `CFBundleVersion` with the executable's Mach-O UUID, so a rebuilt app clears every
 * frame (5.1).
 */
@KeliverFrameFoldApi
public fun iosFrameStore(limits: FrameStoreLimits = FrameStoreLimits()): FrameStore =
  FrameStore(
    fileSystem = FileSystem.SYSTEM,
    directory = iosFrameDirectory(),
    hostBuild = iosHostBuild(),
    clock = object : FrameStoreClock {
      override fun wallMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

      // CLOCK_UPTIME_RAW does not count sleep, which is fine: the wall clock covers that.
      override fun bootMillis(): Long = (clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()) / 1_000_000UL).toLong()
    },
    randomBytes = ::secureRandomBytes,
    limits = limits,
  )

internal fun iosFrameDirectory(): Path {
  val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
    .firstOrNull() as? String ?: error("no Caches directory")
  val directory = caches.toPath() / "keliver-frames"
  FileSystem.SYSTEM.createDirectories(directory)
  NSURL.fileURLWithPath(directory.toString())
    .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
  return directory
}

internal fun secureRandomBytes(count: Int): ByteArray {
  val bytes = ByteArray(count)
  if (count > 0) {
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) }
    check(status == 0) { "SecRandomCopyBytes failed" }
  }
  return bytes
}

internal fun iosHostBuild(): String {
  val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "?"
  val executable = NSBundle.mainBundle.executablePath
  val identity = executable?.let { machOUuid(it) ?: modificationTime(it) } ?: "?"
  return "ios:$version:$identity"
}

private fun modificationTime(path: String): String? {
  val date = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get("NSFileModificationDate")
    as? NSDate ?: return null
  return "mtime-" + (date.timeIntervalSince1970 * 1000.0).toLong()
}

/** The executable's `LC_UUID`, from a thin 64-bit little-endian Mach-O header; null if it is not one. */
internal fun machOUuid(path: String): String? = try {
  val file = path.toPath()
  val length = minOf(64L * 1024, FileSystem.SYSTEM.metadata(file).size ?: 0L)
  val head = FileSystem.SYSTEM.read(file) { readByteArray(length) }
  parseMachOUuid(head)
} catch (_: Exception) {
  null
}

internal fun parseMachOUuid(head: ByteArray): String? {
  fun u32(at: Int): Long? {
    if (at < 0 || at + 4 > head.size) return null
    var v = 0L
    for (i in 3 downTo 0) v = (v shl 8) or (head[at + i].toLong() and 0xff)
    return v
  }
  if (u32(0) != 0xFEEDFACFL) return null
  val count = u32(16) ?: return null
  var offset = 32
  for (n in 0 until count) {
    val cmd = u32(offset) ?: return null
    val size = u32(offset + 4)?.toInt() ?: return null
    if (size < 8) return null
    if (cmd == 0x1BL) {
      if (offset + 24 > head.size) return null
      return head.copyOfRange(offset + 8, offset + 24).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
    offset += size
  }
  return null
}
