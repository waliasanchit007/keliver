/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.image

import coil3.ComponentRegistry
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

/**
 * JVM Coil 3 network fetcher — OkHttp-backed (same as Android).
 * The JVM target group exists primarily to keep testing parity; the
 * production path is Android + iOS.
 */
internal actual fun ComponentRegistry.Builder.installCoilNetworkFetcher() {
  add(OkHttpNetworkFetcherFactory())
}
