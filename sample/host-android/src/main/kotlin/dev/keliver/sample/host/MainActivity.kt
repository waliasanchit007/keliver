/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package dev.keliver.sample.host

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.zipline.Zipline
import app.cash.zipline.ZiplineManifest
import app.cash.zipline.loader.ManifestVerifier
import app.cash.zipline.loader.asZiplineHttpClient
import okio.ByteString.Companion.decodeHex
import dev.keliver.leaks.LeakDetector
import dev.keliver.treehouse.EventListener
import dev.keliver.treehouse.TreehouseApp as KTreehouseApp
import dev.keliver.sample.schema.protocol.host.SampleSchemaHostProtocol
import dev.keliver.sample.shared.SampleAppService
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
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
      // Verify the guest manifest's Ed25519 signature before running any
      // guest code. The matching PRIVATE key signs the bundle in
      // guest/build.gradle.kts; the key NAME must match on both sides.
      // If the signature is missing or forged, Zipline refuses to load
      // the bundle and the EventListener reports codeLoadFailed.
      manifestVerifier = ManifestVerifier.Builder()
        .addEd25519(
          name = "keliver-sample-ed25519",
          trustedKey = SAMPLE_SIGNING_PUBLIC_KEY.decodeHex(),
        )
        .build(),
      embeddedFileSystem = null,
      embeddedDir = null,
      cacheName = "keliver-sample-zipline",
      cacheMaxSizeInBytes = 50L * 1024L * 1024L,
      concurrentDownloads = 4,
      stateStore = MemoryStateStore(),
      leakDetector = LeakDetector.none(),
      hostProtocolFactory = SampleSchemaHostProtocol.Factory,
    )

    val spec = object : TreehouseApp.Spec<SampleAppService>() {
      override val name = "keliver-sample"
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
        // here; see keliver-host for the bundled set.
      }

      override fun create(zipline: Zipline): SampleAppService {
        return zipline.take("app")
      }
    }

    val app = factory.create(
      appScope = lifecycleScope,
      spec = spec,
      eventListenerFactory = LoggingEventListenerFactory,
    )

    setContent {
      SampleHostApp(treehouseApp = app)
    }
  }

  private companion object {
    const val TAG = "KeliverSample"

    // Ed25519 PUBLIC key matching the PRIVATE signing key in
    // guest/build.gradle.kts. Public keys are safe to embed in the app.
    // To rotate: generate a new pair, update both sides + the key name.
    const val SAMPLE_SIGNING_PUBLIC_KEY =
      "98ecb60bb1c418e771378d4451ddfb2f9c440e3ec29cf2f89850ded8f5190cea"
  }
}

/**
 * Dev-time manifest URL constants. Adopters typically:
 *
 *   - During local dev: point at `http://10.0.2.2:8080/manifest.zipline.json`
 *     and run `./gradlew :guest:serveDevelopmentZipline` to serve the
 *     bundle from disk.
 *
 *   - For released builds: bake an HTTPS URL pointing at a CDN. The
 *     manifest is already Ed25519-verified above (see the
 *     `ManifestVerifier.Builder()` wiring); for production, swap the
 *     committed demo key for a real key injected from a secret.
 */
private object LoggingEventListenerFactory : EventListener.Factory {
  override fun create(app: KTreehouseApp<*>, manifestUrl: String?): EventListener =
    LoggingEventListener
  override fun close() {}
}

private object LoggingEventListener : EventListener() {
  override fun ziplineCreated(zipline: Zipline) {
    Log.d(TAG, "ziplineCreated")
  }
  override fun bindService(name: String, service: app.cash.zipline.ZiplineService) {
    Log.d(TAG, "bindService name=$name")
  }
  override fun takeService(name: String, service: app.cash.zipline.ZiplineService) {
    Log.d(TAG, "takeService name=$name")
  }
  override fun codeLoadSuccess(manifest: ZiplineManifest, zipline: Zipline, startValue: Any?) {
    Log.d(TAG, "codeLoadSuccess modules=${manifest.modules.keys.size}")
  }
  override fun codeLoadFailed(exception: Exception, startValue: Any?) {
    Log.e(TAG, "codeLoadFailed: ${exception.message}", exception)
  }
  override fun manifestReady(manifest: ZiplineManifest) {
    Log.d(TAG, "manifestReady modules=${manifest.modules.keys.size}")
  }
  override fun manifestParseFailed(exception: Exception) {
    Log.e(TAG, "manifestParseFailed: ${exception.message}", exception)
  }
  override fun mainFunctionStart(applicationName: String): Any? {
    Log.d(TAG, "mainFunctionStart app=$applicationName")
    return null
  }
  override fun mainFunctionEnd(applicationName: String, startValue: Any?) {
    Log.d(TAG, "mainFunctionEnd app=$applicationName")
  }
  override fun uncaughtException(exception: Throwable) {
    Log.e(TAG, "uncaughtException: ${exception.message}", exception)
  }
  override fun serviceLeaked(name: String) {
    Log.w(TAG, "serviceLeaked name=$name")
  }
  private const val TAG = "KeliverSample"
}

object DevConfig {
  // 10.0.2.2 is the emulator alias for the host machine's localhost.
  // For physical devices on the same Wi-Fi, replace with your laptop's
  // LAN IP.
  const val MANIFEST_URL: String = "http://10.0.2.2:8080/manifest.zipline.json"
}
