/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.image

import coil3.ComponentRegistry
import coil3.network.ktor3.KtorNetworkFetcherFactory

/**
 * iOS Coil 3 network fetcher — Ktor 3 with the Darwin engine.
 *
 * Why Ktor 3 (not Ktor 2): Ktor 3 is the current line and what new
 * iOS adopters will be reaching for. Adopters who are still on
 * Ktor 2 elsewhere in their iOS source set can override the default
 * via the `fetcher = { ... }` parameter on [KonduitImage.installSingleton].
 */
internal actual fun ComponentRegistry.Builder.installCoilNetworkFetcher() {
  add(KtorNetworkFetcherFactory())
}
