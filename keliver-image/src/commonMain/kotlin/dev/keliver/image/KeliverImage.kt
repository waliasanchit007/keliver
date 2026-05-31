/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.image

import androidx.compose.runtime.Composable
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade

/**
 * Configures Coil 3 for the schema's `AsyncImage` widget in one call.
 *
 * The Keliver `AsyncImage` widget renders through Coil's singleton
 * `ImageLoader`. Without an installed singleton, AsyncImage shows a
 * blank rectangle and never errors — the most-reported silent failure
 * in adopter onboarding (see KNOWN_BUGS.md U5). [installSingleton]
 * eliminates that gotcha: one call in your root `@Composable` and the
 * image pipeline works, with a platform-appropriate network fetcher
 * pre-wired (OkHttp on Android / JVM, Ktor 3 + Darwin engine on iOS).
 *
 * Typical adopter usage:
 *
 * ```
 * @Composable
 * fun App(treehouseApp: TreehouseApp<MyAppService>?) {
 *     KeliverImage.installSingleton()
 *     MaterialTheme { /* … your host UI … */ }
 * }
 * ```
 *
 * Customize the underlying `ImageLoader.Builder` via [additional]:
 *
 * ```
 * KeliverImage.installSingleton(
 *     crossfade = true,
 *     additional = {
 *         diskCachePolicy(CachePolicy.ENABLED)
 *         memoryCacheKeyExtras(mapOf("variant" to "dark"))
 *     },
 * )
 * ```
 *
 * Replace the default network fetcher entirely:
 *
 * ```
 * KeliverImage.installSingleton(
 *     fetcher = { /* nothing — disable default */ },
 *     additional = { components { add(MyCustomFetcherFactory()) } },
 * )
 * ```
 */
public object KeliverImage {
  /**
   * @param crossfade Enable Coil's crossfade transition (default true).
   * @param fetcher Configures the network fetcher component. Default
   *   adds the platform-appropriate fetcher (OkHttp on Android / JVM,
   *   Ktor 3 + Darwin engine on iOS). Override with a custom block
   *   to bring your own.
   * @param additional Extra configuration applied to the
   *   `ImageLoader.Builder` after the defaults — disk cache size,
   *   memory cache policy, custom mappers, etc.
   */
  @Composable
  public fun installSingleton(
    crossfade: Boolean = true,
    fetcher: ComponentRegistry.Builder.() -> Unit = { installCoilNetworkFetcher() },
    additional: ImageLoader.Builder.() -> Unit = {},
  ) {
    setSingletonImageLoaderFactory { context ->
      ImageLoader.Builder(context)
        .components { fetcher() }
        .crossfade(crossfade)
        .apply(additional)
        .build()
    }
  }
}

/**
 * Per-platform install of the Coil 3 network fetcher.
 *
 *   - Android: [`OkHttpNetworkFetcherFactory`](https://coil-kt.github.io/coil/api/coil-network-okhttp/)
 *     (no Ktor in the consumer's transitive deps; OkHttp is already
 *     pulled in by Zipline's loader on Android).
 *   - JVM: same — OkHttp.
 *   - iOS: [`KtorNetworkFetcherFactory`](https://coil-kt.github.io/coil/api/coil-network-ktor3/)
 *     with the Darwin engine.
 *
 * Exposed as `internal` so adopters can't accidentally call it
 * outside the [KeliverImage.installSingleton] entry point.
 */
internal expect fun ComponentRegistry.Builder.installCoilNetworkFetcher()
