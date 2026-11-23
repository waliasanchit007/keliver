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
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator

/**
 * W8: makes the NEXT signing key of a rotation (`keliver-publish
 * --new-signing-key <dir>`, docs/KEY_ROTATION.md): `<dir>/ed25519.priv`
 * (owner-only, created with O_EXCL) and `<dir>/ed25519.pub`, in the store's
 * format (raw 32-byte keys as hex, no newline). [dir] is created owner-only
 * when missing; an existing key file there is never replaced. Returns the
 * public key's hex; the private key is never returned, printed or logged.
 */
internal fun writeNewSigningKey(dir: File): String {
  val path = dir.toPath()
  if (!Files.exists(path)) {
    Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
  }
  require(Files.isDirectory(path)) { "$dir is not a directory" }
  val priv = path.resolve(PRIVATE_KEY_FILE)
  val pub = path.resolve(PUBLIC_KEY_FILE)
  require(!Files.exists(priv) && !Files.exists(pub)) { "$dir already holds a key ($PRIVATE_KEY_FILE or $PUBLIC_KEY_FILE); nothing was replaced" }
  val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

  // The JDK wraps raw keys in PKCS#8/X.509; the raw 32 bytes are the last ones.
  fun rawHex(encoded: ByteArray) = encoded.copyOfRange(encoded.size - 32, encoded.size).joinToString("") { "%02x".format(it) }
  fun write(file: java.nio.file.Path, perms: String, text: String) {
    FileChannel.open(
      file,
      setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms)),
    ).use { c ->
      val buffer = ByteBuffer.wrap(text.toByteArray())
      while (buffer.hasRemaining()) c.write(buffer)
      c.force(true)
    }
  }
  write(priv, "rw-------", rawHex(pair.private.encoded))
  val publicHex = rawHex(pair.public.encoded)
  write(pub, "rw-r--r--", publicHex)
  return publicHex
}
