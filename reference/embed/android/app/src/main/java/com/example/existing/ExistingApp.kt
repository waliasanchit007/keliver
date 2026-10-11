package com.example.existing

import android.app.Application
import inventory.host.KeliverHost

class ExistingApp : Application() {
  // KELIVER EMBED: one host per process, owned here; started at launch.
  val keliver by lazy { KeliverHost.create(this) }

  override fun onCreate() {
    super.onCreate()
    keliver.start()
  }
}
