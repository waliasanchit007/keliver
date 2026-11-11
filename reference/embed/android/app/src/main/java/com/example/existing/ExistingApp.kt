package com.example.existing

import android.app.Application
import inventory.host.BuildConfig as KeliverBuildConfig
import inventory.host.KeliverConfig
import inventory.host.KeliverHost
import inventory.host.RequestPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ExistingApp : Application() {
  // KELIVER EMBED: one host per process, owned here; started at launch.
  // The host kit: with this app's RequestPolicy for the guest's HostHttp requests.
  val keliver by lazy { KeliverHost.create(this, KeliverConfig.fromBuild(this, requestPolicy = requestPolicy())) }

  override fun onCreate() {
    super.onCreate()
    keliver.start()
  }
}

/**
 * The host kit (K4): what the guest's HostHttp requests may reach. One origin, and one read,
 * GET ping; nothing state-changing. An app names its own API's origin here; this one takes it
 * from the API base keliver-host was built with (keliver.apiBaseUrl), because the CI harness
 * moves it between an emulator and a phone. Null without an API base: then there is no HostHttp.
 */
private fun requestPolicy(): RequestPolicy? =
  KeliverBuildConfig.KELIVER_API_BASE_URL.toHttpUrlOrNull()?.let { api ->
    RequestPolicy(origin = api, allowed = setOf(RequestPolicy.Rule("GET", "ping")))
  }
