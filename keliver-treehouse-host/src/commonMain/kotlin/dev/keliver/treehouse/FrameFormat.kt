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
package dev.keliver.treehouse

import dev.keliver.protocol.SnapshotChangeList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer

/**
 * The release identity of the app that recorded a frame (docs/SHOW_BEFORE_COMPUTE.md 2.8): the
 * sequence, the manifest's sha256 and a digest of its modules' sha256s.
 */
@KeliverFrameFoldApi
@Serializable
public data class FrameRelease(
  val sequence: Long,
  val manifestSha256: String,
  val moduleDigest: String,
)

/** Every part of a frame's key but the session (the store holds that), docs/SHOW_BEFORE_COMPUTE.md 5.1. */
@KeliverFrameFoldApi
public data class FrameKey(
  val release: FrameRelease,
  /** `hostEngine`. */
  val engine: String,
  val widgetVersion: String,
  /** The schema's tag-lock hash. */
  val schemaHash: String,
  /** The encoded `KeliverRoute`, with its launch arguments. */
  val route: String,
  /**
   * The device's configuration as one string (display, insets, text and contrast, appearance, time;
   * 5.1). A frame for another configuration is another file and is kept.
   */
  val configuration: String,
  /** The app's guest-held theme or language. */
  val variant: String,
)

/** A frame as read back: the snapshot to replay, how much of the strings it keeps, and when it was made. */
@KeliverFrameFoldApi
public class StoredFrame(
  public val snapshot: SnapshotChangeList,
  public val content: FrameFoldContent,
  public val createdAtMillis: Long,
  public val maxAgeMillis: Long,
)

/** The header of a version 1 file (5.2). A field this class does not know is a decode failure. */
@Serializable
internal class FrameHeader(
  val release: FrameRelease,
  val engine: String,
  val hostBuild: String,
  val widgetVersion: String,
  val schemaHash: String,
  val route: String,
  val configuration: String,
  val variant: String,
  val session: String,
  val createdAtMillis: Long,
  val createdAtBoot: Long,
  val maxAgeMillis: Long,
  val content: String,
)

/** Why [FrameStore.read] returned nothing. A reason is a fixed word, never part of a frame. */
@KeliverFrameFoldApi
public enum class FrameMiss {
  /** No session known yet (2.9), the store is off (2.11) or it could not start. */
  Closed,

  /** No file for this route, configuration, variant and session. */
  Absent,

  /** The file's format version, magic or lengths are not version 1's; deleted. */
  Format,

  /** The checksum, the header or the body did not decode; deleted. */
  Corrupt,

  /** A key field of the header differs from the current one; deleted. */
  KeyMismatch,

  /** Older than its `maxAge`, or dated in the future; deleted. */
  Expired,

  /** An unknown content tag; deleted (I1). */
  UnknownContent,

  /** The file system failed. */
  Io,
}

/** Why [FrameStore.write] stored nothing. */
@KeliverFrameFoldApi
public enum class FrameReject {
  /** No session known yet, the store is off, or it could not start. */
  Closed,

  /** The encoded frame is over the per-frame cap (I9). */
  TooLarge,

  /** The session changed while the frame was being written; it was deleted. */
  SessionChanged,

  /** The file system failed. */
  Io,
}

internal object FrameFormat {
  const val VERSION: Int = 1
  private const val MAGIC = "KLVF"
  private const val MAX_HEADER_BYTES = 16 * 1024

  val json: Json = Json

  /** What a file's bytes were found to be. */
  sealed interface Parsed {
    class Ok(val header: FrameHeader, val body: ByteArray) : Parsed
    class Bad(val miss: FrameMiss) : Parsed
  }

  fun encode(header: FrameHeader, snapshot: SnapshotChangeList): ByteArray {
    val headerBytes = json.encodeToString(FrameHeader.serializer(), header).encodeToByteArray()
    val bodyJson = json.encodeToString(SnapshotChangeList.serializer(), snapshot)
    val zipped = Buffer()
    GzipSink(zipped).buffer().use { it.writeUtf8(bodyJson) }
    val body = zipped.readByteArray()
    val out = Buffer()
    out.writeUtf8(MAGIC)
    out.writeByte(VERSION)
    out.writeInt(headerBytes.size)
    out.write(headerBytes)
    out.writeInt(body.size)
    out.write(body)
    val crc = crc32c(out.snapshot().toByteArray())
    out.writeInt(crc)
    return out.readByteArray()
  }

  /** Reads only as far as the header (for the age order and for the clearing scans); no checksum. */
  fun parseHeaderOnly(bytes: ByteArray): FrameHeader? {
    val buffer = Buffer().write(bytes)
    if (buffer.size < 9) return null
    if (buffer.readUtf8(4) != MAGIC || buffer.readByte().toInt() != VERSION) return null
    val length = buffer.readInt()
    if (length < 0 || length > MAX_HEADER_BYTES || buffer.size < length) return null
    return try {
      json.decodeFromString(FrameHeader.serializer(), buffer.readUtf8(length.toLong()))
    } catch (_: Exception) {
      null
    }
  }

  fun parse(bytes: ByteArray, maxFrameBytes: Int): Parsed {
    if (bytes.size > maxFrameBytes) return Parsed.Bad(FrameMiss.Format)
    // magic + version + header length + body length + crc
    if (bytes.size < 4 + 1 + 4 + 4 + 4) return Parsed.Bad(FrameMiss.Format)
    val buffer = Buffer().write(bytes)
    if (buffer.readUtf8(4) != MAGIC) return Parsed.Bad(FrameMiss.Format)
    if (buffer.readByte().toInt() != VERSION) return Parsed.Bad(FrameMiss.Format)
    val headerLength = buffer.readInt()
    if (headerLength < 0 || headerLength > MAX_HEADER_BYTES || buffer.size < headerLength + 8L) {
      return Parsed.Bad(FrameMiss.Format)
    }
    val headerBytes = buffer.readByteArray(headerLength.toLong())
    val bodyLength = buffer.readInt()
    if (bodyLength < 0 || buffer.size != bodyLength + 4L) return Parsed.Bad(FrameMiss.Format)
    val body = buffer.readByteArray(bodyLength.toLong())
    val stored = buffer.readInt()
    if (crc32c(bytes, bytes.size - 4) != stored) return Parsed.Bad(FrameMiss.Corrupt)
    val header = try {
      json.decodeFromString(FrameHeader.serializer(), headerBytes.decodeToString())
    } catch (_: Exception) {
      return Parsed.Bad(FrameMiss.Corrupt)
    }
    return Parsed.Ok(header, body)
  }

  /** Decodes a body, refusing more than [maxDecodedBytes] after decompression. */
  fun decodeBody(body: ByteArray, maxDecodedBytes: Long): SnapshotChangeList? = try {
    val source = GzipSource(Buffer().write(body)).buffer()
    source.use {
      if (it.request(maxDecodedBytes + 1)) return null
      json.decodeFromString(SnapshotChangeList.serializer(), it.readUtf8())
    }
  } catch (_: Exception) {
    null
  }

  private val crcTable: IntArray = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x82F63B78.toInt() else c ushr 1 }
    c
  }

  /** CRC32C (Castagnoli) of the first [length] bytes. */
  fun crc32c(bytes: ByteArray, length: Int = bytes.size): Int {
    var c = -1
    for (i in 0 until length) {
      c = crcTable[(c xor bytes[i].toInt()) and 0xff] xor (c ushr 8)
    }
    return c.inv()
  }
}
