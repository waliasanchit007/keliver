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
@file:OptIn(KeliverFrameFoldApi::class)

package dev.keliver.treehouse

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import dev.keliver.protocol.ChildrenChange.Add
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Create
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierChange
import dev.keliver.protocol.PropertyChange
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.SnapshotChangeList
import dev.keliver.protocol.WidgetTag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path

internal class FakeFrameClock(var wall: Long = 1_000_000L, var boot: Long = 5_000L) : FrameStoreClock {
  override fun wallMillis(): Long = wall

  override fun bootMillis(): Long = boot
}

internal fun snapshotOf(text: String = "Hello"): SnapshotChangeList = SnapshotChangeList(
  listOf(
    Create(Id(1), WidgetTag(1)),
    ModifierChange(Id(1), emptyList()),
    PropertyChange(Id(1), WidgetTag(1), PropertyTag(1), JsonPrimitive(text)),
    Add(Id.Root, ChildrenTag.Root, Id(1), 0),
  ),
)

internal fun textOf(frame: StoredFrame): String =
  ((frame.snapshot.changes[2] as PropertyChange).value as JsonPrimitive).content

internal val baseKey = FrameKey(
  release = FrameRelease(7, "manifest-sha", "module-digest"),
  engine = "quickjs-2026",
  widgetVersion = "w1",
  schemaHash = "schema-1",
  route = "orders/42",
  configuration = "360x800@3 light",
  variant = "en",
)

internal const val CACHE = "orders"

internal fun scratchDirectory(): Path {
  val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "keliver-frame-store-test-${kotlin.random.Random.nextLong().toULong()}"
  FileSystem.SYSTEM.createDirectories(root)
  return root
}

internal class SequentialRandom {
  private var next = 1
  val bytes: (Int) -> ByteArray = { n -> ByteArray(n) { (next++ and 0xff).toByte() } }
}

class FrameStoreTest {
  private val parent = scratchDirectory()
  private val dir = parent / "frames"
  private val clock = FakeFrameClock()
  private val random = SequentialRandom()

  @AfterTest fun cleanUp() {
    FileSystem.SYSTEM.deleteRecursively(parent, mustExist = false)
  }

  private fun store(
    hostBuild: String = "build-1",
    limits: FrameStoreLimits = FrameStoreLimits(),
    fileSystem: FileSystem = FileSystem.SYSTEM,
  ) = FrameStore(fileSystem, dir, hostBuild, clock, random.bytes, limits)

  private fun opened(
    hostBuild: String = "build-1",
    limits: FrameStoreLimits = FrameStoreLimits(),
    fileSystem: FileSystem = FileSystem.SYSTEM,
  ): FrameStore = store(hostBuild, limits, fileSystem).also { it.session("user-1") }

  private fun framesOnDisk(): List<Path> =
    FileSystem.SYSTEM.listRecursively(parent).filter { it.name.endsWith(".klvf") }.toList()

  private fun filesOnDisk(): List<Path> =
    FileSystem.SYSTEM.listRecursively(parent).filter { FileSystem.SYSTEM.metadata(it).isRegularFile }.toList()

  private fun FrameStore.put(key: FrameKey = baseKey, text: String = "Hello", maxAge: Long = 60_000L) =
    write(CACHE, key, snapshotOf(text), FrameFoldContent.Whole, maxAge)

  private fun FrameStore.get(key: FrameKey = baseKey): FrameRead = read(CACHE, key)

  private fun FrameRead.miss(): FrameMiss = (this as? FrameRead.Miss)?.reason ?: fail("expected a miss, got a hit")

  private fun FrameRead.hit(): StoredFrame = (this as? FrameRead.Hit)?.frame ?: fail("expected a hit, got ${(this as FrameRead.Miss).reason}")

  @Test fun roundTrip() {
    val store = opened()
    assertThat(store.put(text = "Hello")).isInstanceOf(FrameWrite.Stored::class)
    val frame = store.get().hit()
    assertThat(textOf(frame)).isEqualTo("Hello")
    assertThat(frame.content).isEqualTo(FrameFoldContent.Whole)
    assertThat(frame.snapshot).isEqualTo(snapshotOf("Hello"))
    assertThat(frame.maxAgeMillis).isEqualTo(60_000L)
  }

  @Test fun shellContentRoundTrips() {
    val store = opened()
    store.write(CACHE, baseKey, snapshotOf(), FrameFoldContent.Shell, 1_000L)
    assertThat(store.get().hit().content).isEqualTo(FrameFoldContent.Shell)
  }

  // ---- I1: one test per key field ----

  @Test fun releaseSequenceChangeMisses() = headerFieldMisses(baseKey.copy(release = baseKey.release.copy(sequence = 8)))

  @Test fun releaseManifestChangeMisses() =
    headerFieldMisses(baseKey.copy(release = baseKey.release.copy(manifestSha256 = "other")))

  @Test fun releaseModuleDigestChangeMisses() =
    headerFieldMisses(baseKey.copy(release = baseKey.release.copy(moduleDigest = "other")))

  @Test fun engineChangeMisses() = headerFieldMisses(baseKey.copy(engine = "quickjs-2021"))

  @Test fun widgetVersionChangeMisses() = headerFieldMisses(baseKey.copy(widgetVersion = "w2"))

  @Test fun schemaHashChangeMisses() = headerFieldMisses(baseKey.copy(schemaHash = "schema-2"))

  /** A field of the header: same file, so the frame is found, refused and deleted. */
  private fun headerFieldMisses(other: FrameKey) {
    val store = opened()
    store.put()
    assertThat(store.get(other).miss()).isEqualTo(FrameMiss.KeyMismatch)
    assertThat(framesOnDisk()).hasSize(0)
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Absent)
  }

  @Test fun routeChangeMissesAndKeepsTheOtherFrame() = fileNameFieldMisses(baseKey.copy(route = "orders/43"))

  @Test fun configurationChangeMissesAndKeepsTheOtherFrame() =
    fileNameFieldMisses(baseKey.copy(configuration = "360x800@3 dark"))

  @Test fun variantChangeMissesAndKeepsTheOtherFrame() = fileNameFieldMisses(baseKey.copy(variant = "hi"))

  /** A field of the file name (5.1 "kept"): another file, so nothing is deleted. */
  private fun fileNameFieldMisses(other: FrameKey) {
    val store = opened()
    store.put()
    assertThat(store.get(other).miss()).isEqualTo(FrameMiss.Absent)
    assertThat(framesOnDisk()).hasSize(1)
    assertThat(textOf(store.get().hit())).isEqualTo("Hello")
  }

  @Test fun cacheNameChangeMisses() {
    val store = opened()
    store.put()
    assertThat(store.read("other-cache", baseKey).miss()).isEqualTo(FrameMiss.Absent)
  }

  @Test fun hostBuildChangeClearsAtFirstStart() {
    opened(hostBuild = "build-1").put()
    assertThat(framesOnDisk()).hasSize(1)
    val updated = store(hostBuild = "build-2")
    updated.session("user-1")
    assertThat(framesOnDisk()).hasSize(0)
    assertThat(updated.get().miss()).isEqualTo(FrameMiss.Absent)
  }

  @Test fun sameHostBuildKeepsFramesAcrossProcesses() {
    opened().put()
    val again = store()
    again.session("user-1")
    assertThat(textOf(again.get().hit())).isEqualTo("Hello")
  }

  @Test fun sessionChangeMisses() {
    val store = opened()
    store.put()
    store.session("user-2")
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Absent)
  }

  @Test fun frameOfAnotherSessionIsNotReadEvenIfTheFileIsPlacedThere() {
    val store = opened()
    store.put()
    val file = framesOnDisk().single()
    val bytes = FileSystem.SYSTEM.read(file) { readByteArray() }
    store.session("user-2")
    // Put the old session's file at the new session's name: the header's session must still refuse it.
    store.put(text = "mine")
    val newFile = framesOnDisk().single()
    FileSystem.SYSTEM.write(newFile) { write(bytes) }
    assertThat(store.get().miss()).isEqualTo(FrameMiss.KeyMismatch)
  }

  @Test fun maxAgeExpiryMisses() {
    val store = opened()
    store.put(maxAge = 10_000L)
    clock.wall += 9_999
    clock.boot += 9_999
    assertThat(textOf(store.get().hit())).isEqualTo("Hello")
    clock.wall += 1
    clock.boot += 1
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Expired)
    assertThat(framesOnDisk()).hasSize(0)
  }

  @Test fun expiryOnTheBootClockEvenIfTheWallClockWasSetBack() {
    val store = opened()
    store.put(maxAge = 10_000L)
    clock.boot += 10_000
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Expired)
  }

  @Test fun aRebootLeavesTheWallClockToDecide() {
    val store = opened()
    store.put(maxAge = 10_000L)
    clock.boot = 10 // rebooted: boot clock restarted
    clock.wall += 5_000
    assertThat(textOf(store.get().hit())).isEqualTo("Hello")
    clock.wall += 5_000
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Expired)
  }

  @Test fun aFrameDatedInTheFutureMisses() {
    val store = opened()
    store.put()
    clock.wall -= 1
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Expired)
  }

  @Test fun unknownContentTagIsDiscarded() {
    val store = opened()
    store.put()
    val file = framesOnDisk().single()
    val old = FrameFormat.parseHeaderOnly(FileSystem.SYSTEM.read(file) { readByteArray() })!!
    val forged = FrameHeader(
      old.release, old.engine, old.hostBuild, old.widgetVersion, old.schemaHash, old.route,
      old.configuration, old.variant, old.session, old.createdAtMillis, old.createdAtBoot,
      old.maxAgeMillis, "mystery",
    )
    FileSystem.SYSTEM.write(file) { write(FrameFormat.encode(forged, snapshotOf())) }
    assertThat(store.get().miss()).isEqualTo(FrameMiss.UnknownContent)
    assertThat(framesOnDisk()).hasSize(0)
  }

  @Test fun unknownFormatVersionIsDiscarded() {
    val store = opened()
    store.put()
    val file = framesOnDisk().single()
    val bytes = FileSystem.SYSTEM.read(file) { readByteArray() }
    bytes[4] = 2
    FileSystem.SYSTEM.write(file) { write(bytes) }
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Format)
    assertThat(framesOnDisk()).hasSize(0)
  }

  @Test fun truncatedFileIsDiscarded() {
    val store = opened()
    store.put()
    val file = framesOnDisk().single()
    val bytes = FileSystem.SYSTEM.read(file) { readByteArray() }
    FileSystem.SYSTEM.write(file) { write(bytes, 0, bytes.size / 2) }
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Format)
    assertThat(framesOnDisk()).hasSize(0)
  }

  // ---- I5 ----

  @Test fun aSingleChangedByteIsRefusedAtEveryPosition() {
    val store = opened()
    store.put()
    val file = framesOnDisk().single()
    val original = FileSystem.SYSTEM.read(file) { readByteArray() }
    for (i in original.indices) {
      val changed = original.copyOf()
      changed[i] = (changed[i].toInt() xor 0x01).toByte()
      FileSystem.SYSTEM.write(file) { write(changed) }
      val read = store.get()
      if (read !is FrameRead.Miss) fail("a frame with byte $i changed was read")
      FileSystem.SYSTEM.write(file) { write(original) } // the store deleted it; put it back
    }
  }

  @Test fun theSessionIdIsNeverStored() {
    val store = store()
    store.session("secret-account-id-12345")
    store.put()
    for (file in filesOnDisk()) {
      val text = FileSystem.SYSTEM.read(file) { readByteString() }.utf8()
      assertThat(text).doesNotContain("secret-account-id-12345")
    }
  }

  @Test fun nothingOutsideTheDirectoryAndNoTemporaryFilesLeft() {
    val store = opened()
    store.put()
    store.put(baseKey.copy(route = "b"))
    store.clear(CACHE)
    store.put()
    for (file in filesOnDisk()) {
      assertThat(file.toString().startsWith(dir.toString())).isTrue()
      assertThat(file.name.endsWith(".tmp")).isEqualTo(false)
    }
  }

  @Test fun storeFailureNeverThrowsAndSaysOnlyFixedReasons() {
    val broken = object : ForwardingFileSystem(FileSystem.SYSTEM) {
      override fun atomicMove(source: Path, target: Path) {
        if (target.name.endsWith(".klvf")) throw okio.IOException("disk gone")
        super.atomicMove(source, target)
      }
    }
    val store = opened(fileSystem = broken)
    val result = store.put()
    assertThat(result).isInstanceOf(FrameWrite.Rejected::class)
    assertThat((result as FrameWrite.Rejected).reason).isEqualTo(FrameReject.Io)
    assertThat(filesOnDisk().filter { it.name.endsWith(".tmp") }).hasSize(0)
  }

  // ---- 2.9: fail closed ----

  @Test fun noReadNoWriteBeforeTheSessionIsKnown() {
    val store = store()
    val result = store.put()
    assertThat((result as FrameWrite.Rejected).reason).isEqualTo(FrameReject.Closed)
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Closed)
    assertThat(framesOnDisk()).hasSize(0)
  }

  @Test fun aStoredFrameIsNotReadByAProcessThatDoesNotKnowTheSessionYet() {
    opened().put()
    val fresh = store()
    assertThat(fresh.get().miss()).isEqualTo(FrameMiss.Closed)
    fresh.session("user-1")
    assertThat(textOf(fresh.get().hit())).isEqualTo("Hello")
  }

  @Test fun declaringNoAccountsOpensTheStore() {
    val store = store()
    store.declareNoAccounts()
    store.put()
    assertThat(textOf(store.get().hit())).isEqualTo("Hello")
  }

  @Test fun signedOutIsASessionOfItsOwn() {
    val store = store()
    store.session(null)
    store.put()
    assertThat(textOf(store.get().hit())).isEqualTo("Hello")
    store.session("user-1")
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Absent)
    assertThat(framesOnDisk()).hasSize(0)
  }

  @Test fun noAccountsThenAnAccountClears() {
    val store = store()
    store.declareNoAccounts()
    store.put()
    store.session("user-1")
    assertThat(framesOnDisk()).hasSize(0)
  }

  @Test fun sessionChangeDeletesEveryFrameOfEveryCacheNameBeforeItReturns() {
    val store = opened()
    store.write("a", baseKey, snapshotOf(), FrameFoldContent.Whole, 1000)
    store.write("b", baseKey, snapshotOf(), FrameFoldContent.Whole, 1000)
    store.write("b", baseKey.copy(route = "x"), snapshotOf(), FrameFoldContent.Whole, 1000)
    assertThat(framesOnDisk()).hasSize(3)
    store.session("user-2")
    assertThat(framesOnDisk()).hasSize(0)
    assertThat(store.read("a", baseKey).miss()).isEqualTo(FrameMiss.Absent)
  }

  @Test fun theSameSessionAgainKeepsFrames() {
    val store = opened()
    store.put()
    store.session("user-1")
    assertThat(textOf(store.get().hit())).isEqualTo("Hello")
  }

  @Test fun aSessionChangeWhileAWriteIsInFlightDeletesTheFrame() {
    lateinit var store: FrameStore
    var fired = false
    val racing = object : ForwardingFileSystem(FileSystem.SYSTEM) {
      override fun atomicMove(source: Path, target: Path) {
        super.atomicMove(source, target)
        if (!fired && target.name.endsWith(".klvf")) {
          fired = true
          store.session("user-2")
        }
      }
    }
    store = opened(fileSystem = racing)
    val result = store.put()
    assertThat((result as FrameWrite.Rejected).reason).isEqualTo(FrameReject.SessionChanged)
    assertThat(framesOnDisk()).hasSize(0)
  }

  @Test fun aStoreThatCannotRecordTheSessionStaysClosed() {
    val broken = object : ForwardingFileSystem(FileSystem.SYSTEM) {
      override fun atomicMove(source: Path, target: Path) {
        if (target.name == "session") throw okio.IOException("no")
        super.atomicMove(source, target)
      }
    }
    val store = opened(fileSystem = broken)
    assertThat((store.put() as FrameWrite.Rejected).reason).isEqualTo(FrameReject.Closed)
  }

  // ---- 2.11: the off switch ----

  @Test fun offDeletesAndStopsAndOnResumes() {
    val store = opened()
    store.put()
    store.write("other", baseKey, snapshotOf(), FrameFoldContent.Whole, 1000)
    store.setOff(CACHE, true)
    assertThat(framesOnDisk()).hasSize(1) // only "other"'s
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Closed)
    assertThat((store.put() as FrameWrite.Rejected).reason).isEqualTo(FrameReject.Closed)
    assertThat(textOf(store.read("other", baseKey).hit())).isEqualTo("Hello")
    store.setOff(CACHE, false)
    store.put()
    assertThat(textOf(store.get().hit())).isEqualTo("Hello")
  }

  @Test fun offSurvivesARestartAndASessionChange() {
    val store = opened()
    store.setOff(CACHE, true)
    store.session("user-2")
    val restarted = store()
    restarted.session("user-2")
    assertThat(restarted.get().miss()).isEqualTo(FrameMiss.Closed)
    assertThat((restarted.put() as FrameWrite.Rejected).reason).isEqualTo(FrameReject.Closed)
  }

  // ---- clearing ----

  @Test fun clearDeletesOneCacheName() {
    val store = opened()
    store.put()
    store.write("b", baseKey, snapshotOf(), FrameFoldContent.Whole, 1000)
    store.clear(CACHE)
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Absent)
    assertThat(textOf(store.read("b", baseKey).hit())).isEqualTo("Hello")
  }

  @Test fun clearRouteDeletesThatRouteInEveryConfiguration() {
    val store = opened()
    store.put()
    store.put(baseKey.copy(configuration = "dark"))
    store.put(baseKey.copy(route = "orders/43"))
    store.clearRoute(CACHE, "orders/42")
    assertThat(framesOnDisk()).hasSize(1)
    assertThat(textOf(store.get(baseKey.copy(route = "orders/43")).hit())).isEqualTo("Hello")
  }

  @Test fun clearAllDeletesEverythingButStaysOpen() {
    val store = opened()
    store.put()
    store.write("b", baseKey, snapshotOf(), FrameFoldContent.Whole, 1000)
    store.clearAll()
    assertThat(framesOnDisk()).hasSize(0)
    store.put()
    assertThat(textOf(store.get().hit())).isEqualTo("Hello")
  }

  @Test fun variantChangedDeletesTheOldVariantsFrames() {
    val store = opened()
    store.put(baseKey.copy(variant = "en"))
    store.put(baseKey.copy(variant = "hi"))
    store.variantChanged(CACHE, "hi")
    assertThat(framesOnDisk()).hasSize(1)
    assertThat(textOf(store.get(baseKey.copy(variant = "hi")).hit())).isEqualTo("Hello")
    assertThat(store.get(baseKey.copy(variant = "en")).miss()).isEqualTo(FrameMiss.Absent)
  }

  // ---- I9: caps ----

  /** Text that does not compress, so a body's size is about the text's. */
  private fun noise(length: Int, seed: Int): String {
    val random = kotlin.random.Random(seed)
    return buildString { repeat(length) { append(('a'.code + random.nextInt(26)).toChar()) } }
  }

  @Test fun aFrameOverThePerFrameCapIsNotStored() {
    val store = opened(limits = FrameStoreLimits(maxFrameBytes = 2_000, maxCacheNameBytes = 10_000))
    val result = store.put(text = noise(5_000, 1))
    assertThat((result as FrameWrite.Rejected).reason).isEqualTo(FrameReject.TooLarge)
    assertThat(framesOnDisk()).hasSize(0)
  }

  @Test fun aFrameUnderThePerFrameCapIsStored() {
    val store = opened(limits = FrameStoreLimits(maxFrameBytes = 4_000, maxCacheNameBytes = 10_000))
    assertThat(store.put(text = noise(1_000, 1))).isInstanceOf(FrameWrite.Stored::class)
  }

  @Test fun aCacheNameOverItsTotalCapEvictsTheOldestFirst() {
    val limits = FrameStoreLimits(maxFrameBytes = 2_000, maxCacheNameBytes = 5_000)
    val store = opened(limits = limits)
    val routes = (1..8).map { "route-$it" }
    for (route in routes) {
      clock.wall += 10
      clock.boot += 10
      store.put(baseKey.copy(route = route), text = noise(1_000, route.hashCode()))
      val total = framesOnDisk().sumOf { FileSystem.SYSTEM.metadata(it).size ?: 0L }
      assertThat(total).isLessThanOrEqualTo(5_000L)
    }
    val present = routes.filter { store.get(baseKey.copy(route = it)) is FrameRead.Hit }
    // The newest are kept, the oldest are gone, and what is kept is a suffix of the write order.
    assertThat(present.last()).isEqualTo("route-8")
    assertThat(present).isEqualTo(routes.takeLast(present.size))
    assertThat(present.size < routes.size).isTrue()
  }

  @Test fun theCapIsPerCacheName() {
    val limits = FrameStoreLimits(maxFrameBytes = 2_000, maxCacheNameBytes = 2_500)
    val store = opened(limits = limits)
    store.write("a", baseKey, snapshotOf(noise(1_000, 1)), FrameFoldContent.Whole, 60_000)
    clock.wall += 10
    clock.boot += 10
    store.write("b", baseKey, snapshotOf(noise(1_000, 2)), FrameFoldContent.Whole, 60_000)
    assertThat(store.read("a", baseKey)).isInstanceOf(FrameRead.Hit::class)
    assertThat(store.read("b", baseKey)).isInstanceOf(FrameRead.Hit::class)
  }

  @Test fun anOversizedFileOnDiskIsNotReadIntoMemory() {
    val store = opened(limits = FrameStoreLimits(maxFrameBytes = 2_000, maxCacheNameBytes = 10_000))
    store.put(text = "hi")
    val file = framesOnDisk().single()
    FileSystem.SYSTEM.write(file) { write(ByteArray(5_000)) }
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Format)
    assertThat(framesOnDisk()).hasSize(0)
  }

  @Test fun aBodyThatInflatesPastTheDecodeLimitIsRefused() {
    val store = opened(limits = FrameStoreLimits(maxFrameBytes = 100_000, maxCacheNameBytes = 200_000, maxDecodedBytes = 1_000))
    store.put(text = "a".repeat(50_000)) // compresses to a few hundred bytes
    assertThat(store.get().miss()).isEqualTo(FrameMiss.Corrupt)
  }

  // ---- I10 ----

  @Test fun twoWritersAndAReaderOnOneKeyTheLastCompleteWriteWins() {
    lateinit var store: FrameStore
    val seen = mutableListOf<String>()
    var fired = false
    val interleaving = object : ForwardingFileSystem(FileSystem.SYSTEM) {
      override fun atomicMove(source: Path, target: Path) {
        if (!fired && target.name.endsWith(".klvf")) {
          fired = true
          // While writer A has only its temporary file: a reader finds nothing, not a partial file.
          seen += (store.get() as? FrameRead.Hit)?.let { textOf(it.frame) } ?: "none"
          // Writer B completes.
          store.put(text = "B")
          seen += textOf(store.get().hit())
        }
        super.atomicMove(source, target)
      }
    }
    store = opened(fileSystem = interleaving)
    store.put(text = "A")
    seen += textOf(store.get().hit())
    assertThat(seen).isEqualTo(listOf("none", "B", "A"))
    assertThat(filesOnDisk().filter { it.name.endsWith(".tmp") }).hasSize(0)
  }

  @Test fun readerNeverSeesATemporaryFile() {
    val store = opened()
    store.put(text = "A")
    // A leftover temporary file next to the frame is not a frame.
    val leftover = framesOnDisk().single().parent!! / "zzz.klvf.deadbeef.tmp"
    FileSystem.SYSTEM.write(leftover) { writeUtf8("garbage") }
    assertThat(textOf(store.get().hit())).isEqualTo("A")
  }

  @Test fun aLeftoverTemporaryFileIsRemovedAtTheNextStart() {
    val store = opened()
    store.put()
    val leftover = framesOnDisk().single().parent!! / "zzz.klvf.deadbeef.tmp"
    FileSystem.SYSTEM.write(leftover) { writeUtf8("garbage") }
    store().session("user-1")
    assertThat(FileSystem.SYSTEM.exists(leftover)).isEqualTo(false)
  }

  @Test fun theInstallKeyIsStableAcrossProcessesSoTheSessionRecordMatches() {
    store().session("user-1")
    val installKey = FileSystem.SYSTEM.read(dir / "install.key") { readByteString() }
    assertThat(installKey.size).isEqualTo(32)
    val again = store()
    again.session("user-1")
    assertThat(FileSystem.SYSTEM.read(dir / "install.key") { readByteString() }).isEqualTo(installKey)
    again.put()
    assertThat(again.get()).isInstanceOf(FrameRead.Hit::class)
  }

  @Test fun theFormatIsVersionedMagicAndChecksummed() {
    val store = opened()
    store.put()
    val bytes = FileSystem.SYSTEM.read(framesOnDisk().single()) { readByteArray() }
    assertThat(bytes.toByteString().utf8().take(4)).isEqualTo("KLVF")
    assertThat(bytes[4].toInt()).isEqualTo(1)
    assertThat(bytes.toByteString().utf8()).contains("manifest-sha")
  }

  @Test fun crc32cMatchesTheStandardCheckValue() {
    // The CRC-32C check value of "123456789" is 0xE3069283.
    assertThat(FrameFormat.crc32c("123456789".encodeToByteArray())).isEqualTo(0xE3069283L.toInt())
  }
}
