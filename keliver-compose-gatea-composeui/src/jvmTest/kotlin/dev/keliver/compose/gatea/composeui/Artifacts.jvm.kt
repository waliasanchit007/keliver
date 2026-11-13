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

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

// The test's working directory is the module: artifacts land in build/gate-a and build/gate-b.
internal actual fun writeArtifact(path: String, bytes: ByteArray) {
  val file = File("build", path)
  file.parentFile.mkdirs()
  file.writeBytes(bytes)
}

internal actual fun writePng(path: String, width: Int, height: Int, pixels: IntArray) {
  val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
  image.setRGB(0, 0, width, height, pixels, 0, width)
  val out = ByteArrayOutputStream()
  ImageIO.write(image, "png", out)
  writeArtifact(path, out.toByteArray())
}
