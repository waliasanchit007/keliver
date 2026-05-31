/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.image

import coil3.ComponentRegistry
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

/**
 * Android Coil 3 network fetcher — OkHttp-backed.
 *
 * Why OkHttp: Zipline's HTTP loader already pulls OkHttp at runtime on
 * Android, so this adds zero transitive weight while keeping Ktor out
 * of the consumer's classpath.
 */
internal actual fun ComponentRegistry.Builder.installCoilNetworkFetcher() {
  add(OkHttpNetworkFetcherFactory())
}
