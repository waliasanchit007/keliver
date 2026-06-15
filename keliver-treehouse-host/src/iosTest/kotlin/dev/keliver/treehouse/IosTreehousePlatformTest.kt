/*
 * Copyright (C) 2023 Square, Inc.
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

import kotlin.test.Test
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

/**
 * Regression guard for docs/KNOWN_BUGS.md (U15): the iOS Zipline cache must live under
 * the device-writable `Library/Caches`, never directly at the sandbox root.
 *
 * NOTE: the original crash (`EPERM` on `createDirectory`) only reproduces on a *physical
 * device* — the simulator's sandbox root is writable, so the writability check below
 * can't fail there. The structural assertions DO fail on the old code, which is the
 * regression we can guard in CI.
 */
class IosTreehousePlatformTest {
  @Test
  fun cacheDirectoryIsUnderLibraryCaches() {
    val dir = iosZiplineCacheDirectory("test-cache")
    val path = dir.toString()
    assertTrue("Library/Caches" in path, "cache dir should be under Library/Caches: $path")
    assertTrue(path.endsWith("test-cache"), "cache dir should end with the cache name: $path")
  }

  @Test
  fun cacheDirectoryIsNotTheSandboxRoot() {
    val dir = iosZiplineCacheDirectory("test-cache")
    val home = NSHomeDirectory().toPath()
    // The old bug placed the cache directly at <home>/<name>; the fix nests it deeper.
    assertTrue(
      dir.parent != home,
      "cache dir must not sit directly under the sandbox root ($home): $dir",
    )
  }

  @Test
  fun cacheDirectoryIsWritable() {
    val fs = FileSystem.SYSTEM
    val dir = iosZiplineCacheDirectory("keliver-u15-writability-probe")
    try {
      fs.createDirectories(dir)
      val probe = dir / "probe.txt"
      fs.write(probe) { writeUtf8("ok") }
      assertTrue(fs.exists(probe))
    } finally {
      fs.deleteRecursively(dir)
    }
  }
}
