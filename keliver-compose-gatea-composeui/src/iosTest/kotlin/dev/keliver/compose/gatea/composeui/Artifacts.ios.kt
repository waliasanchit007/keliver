/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.compose.gatea.composeui

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.getenv

/**
 * The simulator runs the test binary as a host process, so it can write into the module's build
 * directory. The test task passes the directory (`SIMCTL_CHILD_GATE_IOS_ARTIFACTS`, which simctl
 * hands to the child as `GATE_IOS_ARTIFACTS`); without it, artifacts are skipped.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun writeArtifact(path: String, bytes: ByteArray) {
  val root = getenv("GATE_IOS_ARTIFACTS")?.toKString() ?: run {
    println("no GATE_IOS_ARTIFACTS: not writing $path")
    return
  }
  val file = "$root/$path"
  NSFileManager.defaultManager.createDirectoryAtPath(file.substringBeforeLast('/'), true, null, null)
  val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
  if (!data.writeToFile(file, true)) println("could not write $file")
}

internal actual fun writePng(path: String, width: Int, height: Int, pixels: IntArray) {
  // BGRA in memory is an ARGB int written little-endian.
  val bytes = ByteArray(width * height * 4)
  for (i in pixels.indices) {
    val p = pixels[i]
    bytes[i * 4] = p.toByte()
    bytes[i * 4 + 1] = (p shr 8).toByte()
    bytes[i * 4 + 2] = (p shr 16).toByte()
    bytes[i * 4 + 3] = (p shr 24).toByte()
  }
  val image = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4)
  val png = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: return println("could not encode $path")
  writeArtifact(path, png)
}
