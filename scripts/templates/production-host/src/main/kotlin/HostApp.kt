/*
 * This app's Application: it owns the one KeliverHost for the process, and
 * starts it at launch so the lookup, and then the code load (M3), run while
 * the first screen comes up.
 * Scaffolded by keliver-new-production-host.sh (W2).
 */
package @@PACKAGE@@

import android.app.Application

class HostApp : Application() {
  val keliver: KeliverHost by lazy { KeliverHost.create(this) }

  override fun onCreate() {
    super.onCreate()
    keliver.start()
  }
}
