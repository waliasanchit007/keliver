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

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.security.SecureRandom
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * The [FrameStore] for an Android app: frames live in `noBackupFilesDir/keliver-frames`, which is
 * private to the app and excluded from Auto Backup and device transfer (docs/SHOW_BEFORE_COMPUTE.md
 * I5). The host build in the key is the version code with the package's `lastUpdateTime`, so a
 * same-version reinstall or a development build clears every frame (5.1).
 */
@KeliverFrameFoldApi
public fun androidFrameStore(
  context: Context,
  limits: FrameStoreLimits = FrameStoreLimits(),
): FrameStore {
  val random = SecureRandom()
  return FrameStore(
    fileSystem = FileSystem.SYSTEM,
    directory = androidFrameDirectory(context),
    hostBuild = androidHostBuild(context),
    clock = object : FrameStoreClock {
      override fun wallMillis(): Long = System.currentTimeMillis()

      override fun bootMillis(): Long = SystemClock.elapsedRealtime()
    },
    randomBytes = { n -> ByteArray(n).also { random.nextBytes(it) } },
    limits = limits,
  )
}

internal fun androidFrameDirectory(context: Context): Path =
  context.noBackupFilesDir.resolve("keliver-frames").toOkioPath()

@Suppress("DEPRECATION")
internal fun androidHostBuild(context: Context): String {
  val info = context.packageManager.getPackageInfo(context.packageName, 0)
  val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
  return "android:$code:${info.lastUpdateTime}"
}
