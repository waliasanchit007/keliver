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
package dev.keliver.image

import coil3.ComponentRegistry
import coil3.network.ktor3.KtorNetworkFetcherFactory

/**
 * iOS Coil 3 network fetcher — Ktor 3 with the Darwin engine.
 *
 * Why Ktor 3 (not Ktor 2): Ktor 3 is the current line and what new
 * iOS adopters will be reaching for. Adopters who are still on
 * Ktor 2 elsewhere in their iOS source set can override the default
 * via the `fetcher = { ... }` parameter on [KeliverImage.installSingleton].
 */
internal actual fun ComponentRegistry.Builder.installCoilNetworkFetcher() {
  add(KtorNetworkFetcherFactory())
}
