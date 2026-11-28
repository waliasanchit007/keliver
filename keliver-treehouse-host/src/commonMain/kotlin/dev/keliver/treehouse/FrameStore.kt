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
import kotlin.concurrent.Volatile
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/** The two clocks a frame's age is read from: wall time, and time since boot (which a reboot resets). */
@KeliverFrameFoldApi
public interface FrameStoreClock {
  public fun wallMillis(): Long

  public fun bootMillis(): Long
}

/** I9's caps. */
@KeliverFrameFoldApi
public class FrameStoreLimits(
  /** The largest file, header and body together (the proposal: 512 KB). */
  public val maxFrameBytes: Int = 512 * 1024,
  /** The most the files of one `cacheName` may hold; the oldest go first (the proposal: 2 MB). */
  public val maxCacheNameBytes: Long = 2L * 1024 * 1024,
  /** The most a body may inflate to when read. */
  public val maxDecodedBytes: Long = 8L * 1024 * 1024,
) {
  init {
    require(maxFrameBytes > 0 && maxCacheNameBytes >= maxFrameBytes && maxDecodedBytes > 0) {
      "limits must be positive and maxCacheNameBytes at least maxFrameBytes"
    }
  }
}

/** What [FrameStore.read] found. */
@KeliverFrameFoldApi
public sealed interface FrameRead {
  public class Hit(public val frame: StoredFrame) : FrameRead

  public class Miss(public val reason: FrameMiss) : FrameRead
}

/** What [FrameStore.write] did. */
@KeliverFrameFoldApi
public sealed interface FrameWrite {
  public class Stored(public val bytes: Int) : FrameWrite

  public class Rejected(public val reason: FrameReject) : FrameWrite
}

/**
 * NF5 A3b: the cached first frame's store (docs/SHOW_BEFORE_COMPUTE.md 2.8, 2.9, 2.11, 5.1, 5.2 and
 * invariants I1, I5, I9, I10). It keeps what A3a's [ChangeFolder] folds, one file per key, and gives
 * it back only if every part of the key matches.
 *
 * **Where.** [directory] must be app-private storage that is not backed up: Android's
 * `noBackupFilesDir` ([androidFrameStore]), iOS `Library/Caches` excluded from backup
 * ([iosFrameStore]). Frames are files and not preferences (the lastGood record of H0 lives in
 * SharedPreferences and `NSUserDefaults`, which are fit for a few strings and not for blobs); the
 * session record is a file in the same directory so one deletion clears both. Nothing is ever logged
 * or reported here, and results carry fixed reasons, never frame content.
 *
 * **Fail closed (2.9).** Until [session] or [declareNoAccounts] has been called in this process, every
 * [read] misses and every [write] is rejected. So they are while a `cacheName` is switched off
 * ([setOff]) and when the directory cannot be prepared.
 *
 * **Layout.** `<directory>/install.key` (the per-install key, 32 random bytes), `session` (the
 * recorded session, a keyed hash), `host-build`, and `c-<hash of cacheName>/<hash of route,
 * configuration, variant and session>.klvf`. A `cacheName` that is switched off has an `off` marker in
 * its directory.
 *
 * **Key (5.1).** The file name carries route, configuration, variant and session, so a frame for
 * another configuration is another file and is kept. The rest of the key (release, engine, widget
 * version, schema hash, host build) is in the header and is checked before the body is decoded; on a
 * difference the file is deleted. So is a file older than its `maxAge` (wall clock, and boot clock
 * unless the device rebooted) or dated in the future.
 *
 * **One writer, one reader (I10).** A write goes to a unique temporary file, is synced, and is
 * renamed over the final name; a read opens only final names. Two writers of one key leave the last
 * complete rename, and a reader sees either the old file or the new one, whole. The only in-memory
 * state is one immutable value, so the store holds no locks.
 *
 * **Clearing.** [session] (synchronously, before it returns: every frame of every `cacheName`),
 * [setOff], [clear], [clearRoute], [clearAll], [variantChanged] (the old variant's frames), and a
 * changed [hostBuild] (the first call after an app update deletes every frame).
 */
@KeliverFrameFoldApi
public class FrameStore(
  private val fileSystem: FileSystem,
  private val directory: Path,
  /** Which build of the app this is (5.1: Android's version code and `lastUpdateTime`, iOS's build and executable UUID). */
  private val hostBuild: String,
  private val clock: FrameStoreClock,
  /** Cryptographically secure bytes (the per-install key and the temporary names). */
  private val randomBytes: (Int) -> ByteArray,
  private val limits: FrameStoreLimits = FrameStoreLimits(),
) {
  private class State(val session: String?, val generation: Int)

  @Volatile private var state = State(session = null, generation = 0)

  @Volatile private var started = false

  @Volatile private var installKey: ByteString? = null

  /** The app has no accounts (`CachedFrames(accounts = NONE)`). Behaves as a [session] with a fixed value. */
  public fun declareNoAccounts() {
    setSession(NO_ACCOUNTS)
  }

  /**
   * The app's session: [id] is the signed-in account, or null when signed out. When it differs from
   * the recorded one every frame of every `cacheName` is deleted before this returns, and the new
   * record is written. Call it at every start. The id is never stored, only a keyed hash.
   */
  public fun session(id: String?) {
    val key = installKey() ?: return closeAfterFailure()
    setSession(if (id == null) SIGNED_OUT else "h:" + "id:$id".encodeUtf8().hmacSha256(key).hex())
  }

  private fun closeAfterFailure() {
    state = State(session = null, generation = state.generation + 1)
    runCatching { deleteAllFrames() }
  }

  private fun setSession(value: String) {
    if (!ensureStarted()) return closeAfterFailure()
    val recorded = readText(sessionPath)
    if (recorded != value) {
      // Close first, so a writer racing this call cannot rename a frame in for the old session.
      state = State(session = null, generation = state.generation + 1)
      deleteAllFrames()
      if (!writeText(sessionPath, value)) return
    }
    state = State(session = value, generation = state.generation + 1)
  }

  /** Reads the frame for [key] of [cacheName], or says why there is none (I1). */
  public fun read(cacheName: String, key: FrameKey): FrameRead {
    val session = openSession(cacheName) ?: return FrameRead.Miss(FrameMiss.Closed)
    val path = framePath(cacheName, key.route, key.configuration, key.variant, session)
    try {
      val metadata = fileSystem.metadataOrNull(path)
      if (metadata == null || !metadata.isRegularFile) return FrameRead.Miss(FrameMiss.Absent)
      if ((metadata.size ?: Long.MAX_VALUE) > limits.maxFrameBytes) return miss(path, FrameMiss.Format)
      val bytes = fileSystem.read(path) { readByteArray() }
      val parsed = FrameFormat.parse(bytes, limits.maxFrameBytes)
      if (parsed is FrameFormat.Parsed.Bad) return miss(path, parsed.miss)
      parsed as FrameFormat.Parsed.Ok
      val header = parsed.header
      if (!keyMatches(header, key, session)) return miss(path, FrameMiss.KeyMismatch)
      val content = when (header.content) {
        "whole" -> FrameFoldContent.Whole
        "shell" -> FrameFoldContent.Shell
        else -> return miss(path, FrameMiss.UnknownContent)
      }
      if (expired(header)) return miss(path, FrameMiss.Expired)
      val snapshot = FrameFormat.decodeBody(parsed.body, limits.maxDecodedBytes)
        ?: return miss(path, FrameMiss.Corrupt)
      return FrameRead.Hit(StoredFrame(snapshot, content, header.createdAtMillis, header.maxAgeMillis))
    } catch (_: Exception) {
      // Never throws, never says more: a store that cannot read has no frame.
      return FrameRead.Miss(FrameMiss.Io)
    }
  }

  /** Stores [snapshot] under [key]. Writes nothing unless the session is known and the frame fits (I9). */
  public fun write(
    cacheName: String,
    key: FrameKey,
    snapshot: SnapshotChangeList,
    content: FrameFoldContent,
    maxAgeMillis: Long,
  ): FrameWrite {
    val before = state
    val session = openSession(cacheName) ?: return FrameWrite.Rejected(FrameReject.Closed)
    val path = framePath(cacheName, key.route, key.configuration, key.variant, session)
    var temporary: Path? = null
    try {
      val header = FrameHeader(
        release = key.release,
        engine = key.engine,
        hostBuild = hostBuild,
        widgetVersion = key.widgetVersion,
        schemaHash = key.schemaHash,
        route = key.route,
        configuration = key.configuration,
        variant = key.variant,
        session = session,
        createdAtMillis = clock.wallMillis(),
        createdAtBoot = clock.bootMillis(),
        maxAgeMillis = maxAgeMillis,
        content = if (content == FrameFoldContent.Shell) "shell" else "whole",
      )
      val bytes = FrameFormat.encode(header, snapshot)
      if (bytes.size > limits.maxFrameBytes) return FrameWrite.Rejected(FrameReject.TooLarge)
      fileSystem.createDirectories(path.parent!!)
      val tmp = path.parent!! / "${path.name}.${randomBytes(8).toByteString().hex()}.tmp"
      temporary = tmp
      fileSystem.write(tmp) { write(bytes) }
      fileSystem.openReadWrite(tmp).use { it.flush() }
      fileSystem.atomicMove(tmp, path)
      temporary = null
      // A session change while this was in flight: the frame belongs to a session that is gone (2.9).
      val after = state
      if (after.generation != before.generation || after.session != session || readText(sessionPath) != session) {
        delete(path)
        return FrameWrite.Rejected(FrameReject.SessionChanged)
      }
      evict(path.parent!!)
      return FrameWrite.Stored(bytes.size)
    } catch (_: Exception) {
      return FrameWrite.Rejected(FrameReject.Io)
    } finally {
      temporary?.let { delete(it) }
    }
  }

  /** The guest's variant (theme, language) is now [variant]: the frames of every other variant go. */
  public fun variantChanged(cacheName: String, variant: String) {
    forEachFrame(cacheDirectory(cacheName)) { path, header ->
      if (header == null || header.variant != variant) delete(path)
    }
  }

  /**
   * The remote off switch (2.11) for [cacheName]. Off: its frames are deleted and nothing is read or
   * written until it is turned back on. The marker survives the other clearing paths.
   */
  public fun setOff(cacheName: String, off: Boolean) {
    val dir = cacheDirectory(cacheName)
    val marker = dir / OFF_MARKER
    try {
      if (off) {
        fileSystem.createDirectories(dir)
        if (!fileSystem.exists(marker)) fileSystem.write(marker) { writeUtf8("off") }
        deleteFrames(dir)
      } else {
        delete(marker)
      }
    } catch (_: Exception) {
      // An off switch that failed to record still stops this process (below).
    }
    switchedOff = if (off) switchedOff + cacheName else switchedOff - cacheName
  }

  @Volatile private var switchedOff: Set<String> = emptySet()

  /** Deletes every frame of [cacheName] (the app's `clearCachedFrames()`). */
  public fun clear(cacheName: String) {
    deleteFrames(cacheDirectory(cacheName))
  }

  /** Deletes the frames of [route] in [cacheName] (`clearCachedFrames(route)`), whatever their configuration. */
  public fun clearRoute(cacheName: String, route: String) {
    forEachFrame(cacheDirectory(cacheName)) { path, header ->
      if (header == null || header.route == route) delete(path)
    }
  }

  /** Deletes every frame of every `cacheName`. The session record and the install key stay. */
  public fun clearAll() {
    deleteAllFrames()
  }

  // ---- internals ----

  private val sessionPath get() = directory / "session"

  private fun cacheDirectory(cacheName: String): Path =
    directory / ("c-" + cacheName.encodeUtf8().sha256().hex().take(16))

  private fun framePath(cacheName: String, route: String, configuration: String, variant: String, session: String): Path {
    val name = "$route\u0000$configuration\u0000$variant\u0000$session".encodeUtf8().sha256().hex().take(32)
    return cacheDirectory(cacheName) / "$name.klvf"
  }

  /** The session if the store may read and write for [cacheName] now; null if it must refuse. */
  private fun openSession(cacheName: String): String? {
    val session = state.session ?: return null
    if (!started) return null
    if (cacheName in switchedOff) return null
    return try {
      if (fileSystem.exists(cacheDirectory(cacheName) / OFF_MARKER)) null else session
    } catch (_: Exception) {
      null
    }
  }

  /** The first call of a process: the directory exists, and an app update clears every frame (5.1). */
  private fun ensureStarted(): Boolean {
    if (started) return true
    return try {
      fileSystem.createDirectories(directory)
      for (entry in fileSystem.list(directory)) {
        // Frames from a writer that was killed between the write and the rename.
        if (fileSystem.metadataOrNull(entry)?.isDirectory == true) {
          fileSystem.listOrNull(entry)?.filter { it.name.endsWith(".tmp") }?.forEach { delete(it) }
        }
      }
      if (readText(hostBuildPath) != hostBuild) {
        deleteAllFrames()
        if (!writeText(hostBuildPath, hostBuild)) return false
      }
      started = true
      true
    } catch (_: Exception) {
      false
    }
  }

  private val hostBuildPath get() = directory / "host-build"

  private fun installKey(): ByteString? {
    installKey?.let { return it }
    if (!ensureStarted()) return null
    return try {
      val path = directory / "install.key"
      val existing = if (fileSystem.exists(path)) fileSystem.read(path) { readByteString() } else null
      val key = if (existing != null && existing.size == KEY_BYTES) {
        existing
      } else {
        val fresh = randomBytes(KEY_BYTES).toByteString()
        if (fresh.size != KEY_BYTES) return null
        writeAtomically(path, fresh.toByteArray())
        fresh
      }
      installKey = key
      key
    } catch (_: Exception) {
      null
    }
  }

  private fun keyMatches(header: FrameHeader, key: FrameKey, session: String): Boolean =
    header.release == key.release &&
      header.engine == key.engine &&
      header.hostBuild == hostBuild &&
      header.widgetVersion == key.widgetVersion &&
      header.schemaHash == key.schemaHash &&
      header.route == key.route &&
      header.configuration == key.configuration &&
      header.variant == key.variant &&
      header.session == session

  private fun expired(header: FrameHeader): Boolean {
    val maxAge = header.maxAgeMillis
    if (maxAge <= 0) return true
    val wall = clock.wallMillis() - header.createdAtMillis
    if (wall < 0 || wall >= maxAge) return true
    val boot = clock.bootMillis()
    // After a reboot the boot clock starts again: only the wall clock can speak then.
    return boot >= header.createdAtBoot && boot - header.createdAtBoot >= maxAge
  }

  private fun miss(path: Path, reason: FrameMiss): FrameRead.Miss {
    delete(path)
    return FrameRead.Miss(reason)
  }

  private fun delete(path: Path) {
    try {
      fileSystem.delete(path, mustExist = false)
    } catch (_: Exception) {
      // Nothing more to do; a frame that stays is checked again when read.
    }
  }

  private fun deleteFrames(dir: Path) {
    val entries = try {
      fileSystem.listOrNull(dir)
    } catch (_: Exception) {
      null
    } ?: return
    for (entry in entries) {
      if (entry.name.endsWith(".klvf") || entry.name.endsWith(".tmp")) delete(entry)
    }
  }

  private fun deleteAllFrames() {
    val entries = try {
      fileSystem.listOrNull(directory)
    } catch (_: Exception) {
      null
    } ?: return
    for (entry in entries) {
      if (fileSystem.metadataOrNull(entry)?.isDirectory == true) deleteFrames(entry)
    }
  }

  /** Calls [action] for each `.klvf` in [dir] with its header (null if unreadable). */
  private fun forEachFrame(dir: Path, action: (Path, FrameHeader?) -> Unit) {
    val entries = try {
      fileSystem.listOrNull(dir)
    } catch (_: Exception) {
      null
    } ?: return
    for (entry in entries) {
      if (!entry.name.endsWith(".klvf")) continue
      val header = try {
        FrameFormat.parseHeaderOnly(fileSystem.read(entry) { readByteArray() })
      } catch (_: Exception) {
        null
      }
      action(entry, header)
    }
  }

  /** I9: keeps [dir] within the cap, deleting the oldest frames first. */
  private fun evict(dir: Path) {
    class Entry(val path: Path, val size: Long, val createdAt: Long)
    val files = mutableListOf<Entry>()
    forEachFrame(dir) { path, header ->
      val size = fileSystem.metadataOrNull(path)?.size ?: 0L
      files += Entry(path, size, header?.createdAtMillis ?: Long.MIN_VALUE)
    }
    var total = files.sumOf { it.size }
    if (total <= limits.maxCacheNameBytes) return
    for (entry in files.sortedWith(compareBy<Entry> { it.createdAt }.thenBy { it.path.name })) {
      if (total <= limits.maxCacheNameBytes) break
      delete(entry.path)
      total -= entry.size
    }
  }

  private fun readText(path: Path): String? = try {
    if (fileSystem.exists(path)) fileSystem.read(path) { readUtf8() } else null
  } catch (_: Exception) {
    null
  }

  private fun writeText(path: Path, text: String): Boolean = try {
    writeAtomically(path, text.encodeToByteArray())
    true
  } catch (_: Exception) {
    false
  }

  private fun writeAtomically(path: Path, bytes: ByteArray) {
    val tmp = path.parent!! / "${path.name}.${randomBytes(8).toByteString().hex()}.tmp"
    try {
      fileSystem.write(tmp) { write(bytes) }
      fileSystem.openReadWrite(tmp).use { it.flush() }
      fileSystem.atomicMove(tmp, path)
    } catch (e: Exception) {
      delete(tmp)
      throw e
    }
  }

  private companion object {
    const val SIGNED_OUT = "out"
    const val NO_ACCOUNTS = "none"
    const val OFF_MARKER = "off"
    const val KEY_BYTES = 32
  }
}
