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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.Test
import okio.FileSystem

class IosFrameStoreTest {
  @Test fun theDirectoryIsUnderLibraryCaches() {
    val directory = iosFrameDirectory()
    assertThat(directory.toString()).contains("/Library/Caches/keliver-frames")
    assertThat(FileSystem.SYSTEM.exists(directory)).isEqualTo(true)
  }

  @Test fun secureRandomGivesTheRequestedLengthAndVaries() {
    val a = secureRandomBytes(32)
    val b = secureRandomBytes(32)
    assertThat(a.size).isEqualTo(32)
    assertThat(a.toList() == b.toList()).isEqualTo(false)
  }

  @Test fun theHostBuildNamesTheBuildAndTheExecutable() {
    val build = iosHostBuild()
    assertThat(build).contains("ios:")
  }

  @Test fun machOUuidIsFoundInAThinHeader() {
    val header = ByteArray(80)
    // magic 0xFEEDFACF little-endian, ncmds = 1 at offset 16
    header[0] = 0xCF.toByte(); header[1] = 0xFA.toByte(); header[2] = 0xED.toByte(); header[3] = 0xFE.toByte()
    header[16] = 1
    header[32] = 0x1B; header[36] = 24 // LC_UUID, cmdsize 24
    for (i in 0 until 16) header[40 + i] = (i + 1).toByte()
    assertThat(parseMachOUuid(header)).isEqualTo("0102030405060708090a0b0c0d0e0f10")
    assertThat(parseMachOUuid(ByteArray(80))).isEqualTo(null)
  }

  @Test fun aStoreOnTheSimulatorRoundTripsAFrame() {
    val parent = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "keliver-ios-frame-test-${kotlin.random.Random.nextLong().toULong()}"
    FileSystem.SYSTEM.createDirectories(parent)
    try {
      val store = FrameStore(
        FileSystem.SYSTEM,
        parent / "frames",
        iosHostBuild(),
        FakeFrameClock(),
        ::secureRandomBytes,
      )
      assertThat(store.read(CACHE, baseKey)).isInstanceOf(FrameRead.Miss::class)
      store.session("user-1")
      assertThat(store.write(CACHE, baseKey, snapshotOf(), FrameFoldContent.Whole, 60_000L)).isInstanceOf(FrameWrite.Stored::class)
      val read = store.read(CACHE, baseKey)
      assertThat((read as FrameRead.Hit).frame).isNotNull()
      store.session("user-2")
      assertThat(store.read(CACHE, baseKey)).isInstanceOf(FrameRead.Miss::class)
    } finally {
      FileSystem.SYSTEM.deleteRecursively(parent, mustExist = false)
    }
  }
}
