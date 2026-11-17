/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(dev.keliver.leaks.RedwoodLeakApi::class)

package dev.keliver.sample.host.threeway

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.cash.zipline.Zipline
import app.cash.zipline.loader.ManifestVerifier
import dev.keliver.compose.gatea.protocol.host.KeliverComposeGateAHostProtocol
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.HttpRequest
import dev.keliver.http.HttpResponse
import dev.keliver.leaks.LeakDetector
import dev.keliver.sample.threeway.ThreewayAppService
import dev.keliver.sample.threeway.ThreewayHost
import dev.keliver.treehouse.MemoryStateStore
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseAppFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.modules.EmptySerializersModule
import okio.ByteString.Companion.decodeHex

/**
 * NF4 (docs/THREE_WAY_BENCHMARK.md, "Cold start (NF0)", items 2 and 3), prototype builds only:
 *
 * - `x=prestart`: the guest's code load starts at process start, from [ThreewayPrestartProvider]
 *   (a ContentProvider: its onCreate runs in bindApplication, before the Application's and long
 *   before the activity's), instead of after the activity's first frame. The provider cannot see
 *   the launching intent, so it acts on what the PREVIOUS launch asked for ([remember]): the first
 *   launch of a series does not prestart, every later one does. Its main-thread cost is marked
 *   (`n_prestartMainMs` in the `"cold"` line; the benchmark's own fixture API, started there too
 *   with `localApi`, apart as `n_fixtureMainMs`).
 * - `x=prefetch`: the list's first request (`GET /items?size=small&seq=1`, which the screen's
 *   first composition asks for) is sent by the host as it starts the guest (at process start with
 *   `prestart`, else in the activity's onCreate), and the guest's identical request takes its
 *   response ([BenchHostHttp]); the guest is unchanged (D2).
 *
 * Without either experiment nothing here runs, and the guest form starts as before.
 */
internal object ThreewayGuest {
  private const val PREFS = "threeway-nf4"
  private const val MANIFEST_URL = "https://threeway.invalid/manifest.zipline.json"
  private const val SAMPLE_SIGNING_PUBLIC_KEY = "98ecb60bb1c418e771378d4451ddfb2f9c440e3ec29cf2f89850ded8f5190cea"

  /** The process's marks and the guest's host services: one per process, so a prestarted guest can use them. */
  val recorder = Recorder()

  /** The run's experiments as the guest reads them (ThreewayHost.experiments); set before any content binds. */
  @Volatile var experiments: String = ""

  /** The activity's handler for the guest's `composed` report (main thread is not required). */
  @Volatile var onComposed: ((screen: String, seq: Int, marksJson: String) -> Unit)? = null

  private val host = object : ThreewayHost {
    override fun composed(screen: String, seq: Int, marksJson: String) {
      onComposed?.invoke(screen, seq, marksJson)
    }

    override fun experiments(): String = experiments
  }

  val prefetch = FirstRequestPrefetch()

  /** The prestarted app and the experiments it was started with; null when this process did not prestart. */
  private var prestarted: Pair<TreehouseApp<ThreewayAppService>, String>? = null

  private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  /**
   * Records what this launch asked for, for the next process's [ThreewayPrestartProvider]: whether
   * it prestarts, and with which experiments and API.
   */
  fun remember(context: Context, form: String, experiments: String, localApi: Boolean) {
    val set = experiments.split(',').map { it.trim() }.toSet()
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putBoolean("prestart", form == "guest" && "prestart" in set)
      .putString("x", experiments)
      .putBoolean("localApi", localApi)
      .apply()
  }

  /** Main thread, from the provider: starts the code load if the previous launch asked for it. */
  fun prestartIfAsked(context: Context) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean("prestart", false)) return
    ColdStart.mark("prestart")
    val x = prefs.getString("x", "").orEmpty()
    // The benchmark's own fixture API (not part of a host's start), measured apart.
    if (prefs.getBoolean("localApi", false)) {
      val fixtureBegan = SystemClock.elapsedRealtimeNanos()
      LocalFixture.start()
      ColdStart.mainThreadNanos("fixtureMain", SystemClock.elapsedRealtimeNanos() - fixtureBegan)
    }
    val began = SystemClock.elapsedRealtimeNanos()
    experiments = x
    ThreewayListener.applyExperiments(x)
    if ("prefetch" in x.split(',').map { it.trim() }) prefetch.start()
    val app = create(context.applicationContext, processScope)
    app.start()
    prestarted = app to x
    ColdStart.mainThreadNanos("prestartMain", SystemClock.elapsedRealtimeNanos() - began)
    Log.i("Threeway", "prestart: the guest's code load started at process start (x=$x)")
  }

  /** The prestarted app, if this process has one started with [experiments]; it is handed out once. */
  fun takePrestarted(experiments: String): TreehouseApp<ThreewayAppService>? {
    val p = prestarted ?: return null
    prestarted = null
    if (p.second != experiments) {
      Log.w("Threeway", "prestart: started with x=${p.second}, this launch has x=$experiments; not used")
      return null
    }
    return p.first
  }

  fun create(context: Context, scope: CoroutineScope): TreehouseApp<ThreewayAppService> {
    val factory = TreehouseAppFactory(
      context = context,
      httpClient = AssetsHttpClient(context.assets),
      manifestVerifier = ManifestVerifier.Builder()
        .addEd25519(name = "keliver-sample-ed25519", trustedKey = SAMPLE_SIGNING_PUBLIC_KEY.decodeHex())
        .build(),
      cacheName = "keliver-threeway-zipline",
      stateStore = MemoryStateStore(),
      leakDetector = LeakDetector.none(),
      hostProtocolFactory = KeliverComposeGateAHostProtocol.Factory,
    )
    val spec = object : TreehouseApp.Spec<ThreewayAppService>() {
      override val name = "keliver-threeway"
      override val manifestUrl = MutableStateFlow(MANIFEST_URL)
      override val serializersModule = EmptySerializersModule()
      override suspend fun bindServices(treehouseApp: TreehouseApp<ThreewayAppService>, zipline: Zipline) {
        zipline.bind<HostHttpProvider>("HostHttp", BenchHostHttp(ThreewayHttp.okHttp, recorder, prefetch))
        zipline.bind<ThreewayHost>("threewayHost", host)
      }
      override fun create(zipline: Zipline): ThreewayAppService = zipline.take("app")
    }
    return factory.create(appScope = scope, spec = spec, eventListenerFactory = ThreewayListener)
  }
}

/**
 * The list's first request, sent by the host before the guest asks for it (`x=prefetch`). Only an
 * identical GET (path and query) takes it, once; a failed or non-200 prefetch is not used, and the
 * guest's own request goes out as before.
 */
internal class FirstRequestPrefetch {
  private val path = "/items"
  private val query = mapOf("size" to "small", "seq" to "1")
  @Volatile private var pending: Deferred<HttpResponse>? = null

  /** Sends the declared request now, unless it was already sent; from any thread. */
  @Synchronized
  fun start() {
    if (pending != null) return
    ColdStart.mark("prefetchStart")
    pending = CoroutineScope(Dispatchers.IO).async {
      BenchHostHttp.send(ThreewayHttp.okHttp, HttpRequest(method = "GET", path = path, query = query))
        .also { ColdStart.mark("prefetchDone") }
    }
  }

  /** The prefetched response for [request], if it is the declared one and nothing took it yet. */
  @Synchronized
  fun take(request: HttpRequest): Deferred<HttpResponse>? {
    val p = pending ?: return null
    if (request.method.uppercase() != "GET" || request.path != path || request.query != query || request.body != null) return null
    pending = null
    return p
  }
}

/**
 * Runs [ThreewayGuest.prestartIfAsked] in bindApplication. Declared in the prototype's manifest
 * (src/gateb), not exported; it serves no data.
 */
class ThreewayPrestartProvider : ContentProvider() {
  override fun onCreate(): Boolean {
    val context = context ?: return false
    runCatching { ThreewayGuest.prestartIfAsked(context) }
      .onFailure { Log.e("Threeway", "prestart failed; the activity starts the guest as before", it) }
    return true
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
