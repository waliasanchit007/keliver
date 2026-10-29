package com.example.older

import android.app.Application
import okhttp3.OkHttpClient

class OlderApp : Application() {
  // The app's own client, written against OkHttp 4's API.
  val http: OkHttpClient by lazy { OkHttpClient.Builder().build() }

  val keliver by lazy { dev.keliver.floor.host.KeliverHost.create(this) } // KELIVER EMBED
}
