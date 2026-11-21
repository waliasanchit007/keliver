package @@PACKAGE@@

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UISceneWillEnterForegroundNotification
import platform.darwin.NSObjectProtocol

/**
 * NF4 (cold start), iOS: whether this process is starting toward a screen, the moment the app's
 * declared first requests may go out (HostPrefetch.kt). Android's equivalent is the first
 * activity's creation. Here it is the first of:
 * - the host starting while the app is not in the background (a launch the user made: the app is
 *   inactive while it launches, or already active when the host starts late);
 * - a scene entering the foreground (a scene-based app, SwiftUI's included, is still in the
 *   background state while it finishes launching, before its first scene connects), or the app
 *   entering it, or becoming active;
 * - a Keliver screen being made ([screenShown]).
 *
 * A process launched in the background (a push, a background fetch or refresh, a location event)
 * shows nothing and sends nothing, until the user brings the app to the foreground in that process.
 *
 * Main thread only: UIKit posts these notifications on it, and the host starts on it.
 */
internal class UiStartWatch(
  /** Whether the app is anywhere but in the background now (UIApplication's state). */
  private val inForeground: () -> Boolean = {
    UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground
  },
  private val center: NSNotificationCenter = NSNotificationCenter.defaultCenter,
) {
  private var started = false
  private var waiting: List<() -> Unit> = emptyList()
  private var observers: List<NSObjectProtocol>? = null

  /** [then] runs once the process is starting toward a screen: now if it is, otherwise when it does. */
  fun whenStarted(then: () -> Unit) {
    if (started) return then()
    waiting = waiting + then
    if (observers != null) return
    if (inForeground()) return uiStarting()
    observers = UI_START_NOTIFICATIONS.map { name ->
      // A null queue: the block runs where the notification is posted (UIKit: the main thread).
      center.addObserverForName(name, null, null) { _ -> uiStarting() }
    }
  }

  /** A Keliver screen is being made: this process shows a screen. */
  fun screenShown() = uiStarting()

  private fun uiStarting() {
    if (started) return
    started = true
    observers?.forEach { center.removeObserver(it) }
    observers = null
    val then = waiting
    waiting = emptyList()
    then.forEach { it() }
  }

  internal companion object {
    val UI_START_NOTIFICATIONS = listOf(
      UISceneWillEnterForegroundNotification,
      UIApplicationWillEnterForegroundNotification,
      UIApplicationDidBecomeActiveNotification,
    )
  }
}
