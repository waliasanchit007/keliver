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

import app.cash.zipline.loader.LoaderEventListener
import app.cash.zipline.loader.ZiplineCache
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

internal class IosTreehousePlatform : TreehousePlatform {
  override fun newCache(
    name: String,
    maxSizeInBytes: Long,
    loaderEventListener: LoaderEventListener,
  ): ZiplineCache = ZiplineCache(
    fileSystem = FileSystem.SYSTEM,
    directory = iosZiplineCacheDirectory(name),
    maxSizeInBytes = maxSizeInBytes,
    loaderEventListener = loaderEventListener,
  )

  override fun newDispatchers(applicationName: String): TreehouseDispatchers =
    IosTreehouseDispatchers(applicationName)
}

/**
 * Resolves the on-device Zipline cache directory: `<sandbox>/Library/Caches/[name]`.
 *
 * Do NOT place the cache under [platform.Foundation.NSHomeDirectory] (the app
 * container root) directly: on the **simulator** that path is writable, but on a
 * **physical device** only `Documents/`, `Library/`, `Library/Caches/`, and `tmp/`
 * under the sandbox are writable. Creating the cache dir at the sandbox root returns
 * `EPERM` (`okio.IOException: Operation not permitted`) and crashes the app at startup.
 * `Library/Caches` always exists, so ZiplineCache only has to create the [name] leaf.
 * See docs/KNOWN_BUGS.md (U15).
 */
internal fun iosZiplineCacheDirectory(name: String): Path {
  val paths = NSSearchPathForDirectoriesInDomains(
    directory = NSCachesDirectory,
    domainMask = NSUserDomainMask,
    expandTilde = true,
  )
  val cachesPath = paths.firstOrNull() as? String
    ?: error("could not resolve NSCachesDirectory")
  return cachesPath.toPath() / name
}
