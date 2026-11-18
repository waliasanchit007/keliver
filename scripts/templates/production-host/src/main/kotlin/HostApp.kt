/*
 * This app's Application: it owns the one KeliverHost for the process, and
 * starts it at launch so the lookup, and then the code load (M3), run while
 * the first screen comes up.
 * Scaffolded by keliver-new-production-host.sh (W2).
 */
package @@PACKAGE@@

import android.app.Application

class HostApp : Application() {
  // NF4: to have your first screen's data on its way while the bundle loads, declare the GET it
  // makes first (HostPrefetch.kt has the rules):
  //   KeliverHost.create(this, KeliverConfig.fromBuild(this, prefetch = listOf(KeliverPrefetch("/items", mapOf("page" to "1")))))
  // It goes out with your client's credentials as they are when the first activity is created, so
  // restore your session BEFORE keliver.start() below, and call keliver.discardPrefetched() when
  // the user signs in, signs out or changes. If your first activity establishes the session (a PIN,
  // biometric or login screen), don't declare a prefetch.
  val keliver: KeliverHost by lazy { KeliverHost.create(this) }

  override fun onCreate() {
    super.onCreate()
    keliver.start()
  }
}
