/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(dev.konduit.leaks.RedwoodLeakApi::class)

package dev.konduit.sample.host

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.zipline.Zipline
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.asZiplineHttpClient
import dev.konduit.leaks.LeakDetector
import dev.konduit.sample.schema.protocol.host.SampleSchemaHostProtocol
import dev.konduit.sample.shared.SampleAppService
import dev.konduit.treehouse.MemoryStateStore
import dev.konduit.treehouse.TreehouseApp
import dev.konduit.treehouse.TreehouseAppFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.modules.EmptySerializersModule
import okhttp3.OkHttpClient
import androidx.lifecycle.lifecycleScope

/**
 * Minimal Android host. Wiring summary:
 *
 *   1. Build a `TreehouseAppFactory` once. It owns the Zipline cache,
 *      the HTTP client used to fetch the bundle, and the host-protocol
 *      factory generated from the schema.
 *
 *   2. Subclass `TreehouseApp.Spec<SampleAppService>` so the factory
 *      knows (a) where to find the manifest, (b) what host services
 *      to bind for the guest to take(), and (c) how to get the
 *      `SampleAppService` instance back out once the guest's
 *      `main()` has bound it.
 *
 *   3. Mount `TreehouseContent { app.zipline.run { launch() } }` in
 *      the Compose tree (done in [SampleHostApp]).
 *
 * For dev, point [DevConfig.MANIFEST_URL] at a local Zipline-serve
 * task (`./gradlew :guest:serveDevelopmentZipline` exposes the bundle
 * on `http://localhost:8080`). 10.0.2.2 is the emulator's alias for
 * the host machine's localhost.
 */
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "onCreate — manifest URL: ${DevConfig.MANIFEST_URL}")

    val httpClient = OkHttpClient()
    val manifestUrlFlow = MutableStateFlow(DevConfig.MANIFEST_URL)

    val factory = TreehouseAppFactory(
      context = applicationContext,
      httpClient = httpClient.asZiplineHttpClient(),
      manifestVerifier = ManifestVerifier.NO_SIGNATURE_CHECKS,
      embeddedFileSystem = null,
      embeddedDir = null,
      cacheName = "konduit-sample-zipline",
      cacheMaxSizeInBytes = 50L * 1024L * 1024L,
      concurrentDownloads = 4,
      stateStore = MemoryStateStore(),
      leakDetector = LeakDetector.none(),
      hostProtocolFactory = SampleSchemaHostProtocol.Factory,
    )

    val spec = object : TreehouseApp.Spec<SampleAppService>() {
      override val name = "konduit-sample"
      override val manifestUrl = manifestUrlFlow.asStateFlow()
      override val serializersModule = EmptySerializersModule()

      // Spec instance is rooted by the returned TreehouseApp, so any
      // bound services we want to keep alive can live as `val`
      // fields here. This sample binds no host services — the guest
      // only consumes `SampleAppService` via the standard "app" name.

      override suspend fun bindServices(
        treehouseApp: TreehouseApp<SampleAppService>,
        zipline: Zipline,
      ) {
        // No host-bound services in this minimal sample. Adopters
        // typically bind a HostConsole / HostHttp / domain services
        // here; see konduit-host for the bundled set.
      }

      override fun create(zipline: Zipline): SampleAppService {
        return zipline.take("app")
      }
    }

    val app = factory.create(
      appScope = lifecycleScope,
      spec = spec,
    )

    setContent {
      SampleHostApp(treehouseApp = app)
    }
  }

  private companion object {
    const val TAG = "KonduitSample"
  }
}

/**
 * Dev-time manifest URL constants. Adopters typically:
 *
 *   - During local dev: point at `http://10.0.2.2:8080/manifest.zipline.json`
 *     and run `./gradlew :guest:serveDevelopmentZipline` to serve the
 *     bundle from disk.
 *
 *   - For released builds: bake an HTTPS URL pointing at a CDN +
 *     swap to `ManifestVerifier.SignatureChecks(...)` with the
 *     production verifying key.
 */
object DevConfig {
  // 10.0.2.2 is the emulator alias for the host machine's localhost.
  // For physical devices on the same Wi-Fi, replace with your laptop's
  // LAN IP.
  const val MANIFEST_URL: String = "http://10.0.2.2:8080/manifest.zipline.json"
}
