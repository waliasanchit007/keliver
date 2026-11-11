/*
 * Copyright (C) 2024 Square, Inc.
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
@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.keliver.leaks

// As on js: the engine's gc() where it is exposed (V8's --expose-gc), otherwise none.
internal actual fun detectGc(): Gc {
  if (jsHasGlobalGc()) {
    return GlobalThisGc()
  }
  return Gc.None
}

private class GlobalThisGc : Gc {
  override suspend fun collect() {
    jsGlobalGc()
  }
}

private fun jsHasGlobalGc(): Boolean = js("typeof globalThis.gc === 'function'")

private fun jsGlobalGc(): Unit = js("globalThis.gc()")
