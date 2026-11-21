package @@PACKAGE@@

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIScene
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UISceneActivationStateForegroundInactive
import platform.UIKit.UISceneWillEnterForegroundNotification
import platform.darwin.NSObjectProtocol

/**
 * NF4 (cold start), iOS: whether this process is starting toward a screen, the moment the app's
 * declared first requests may go out (HostPrefetch.kt). Android's equivalent is the first
 * activity's creation. Here it is the first of:
 * - the host starting while a scene is in the foreground ([sceneInForeground]: a host started
 *   late, from a screen or after launch);
 * - a scene entering the foreground (a host started in the App's init() or at didFinishLaunching:
 *   no scene is connected yet, and the application state means nothing then), or the app
 *   entering it, or becoming active;
 * - a Keliver screen being made ([screenShown]).
 *
 * A process launched in the background (a push, a background fetch or refresh, a location event)
 * shows nothing and sends nothing, until the user brings the app to the foreground in that process.
 *
 * Main thread only: UIKit posts these notifications on it, and the host starts on it.
 */
internal class UiStartWatch(
  /** Whether a scene of this process is in the foreground now ([sceneInForeground]). */
  private val inForeground: () -> Boolean = ::sceneInForeground,
  private val center: NSNotificationCenter = NSNotificationCenter.defaultCenter,
  /** Told once, at the UI start, what it was (the host logs and marks it in a debug build). */
  private val onUiStart: (cause: String) -> Unit = {},
) {
  private var started = false
  private var waiting: List<() -> Unit> = emptyList()
  private var observers: List<NSObjectProtocol>? = null

  /** [then] runs once the process is starting toward a screen: now if it is, otherwise when it does. */
  fun whenStarted(then: () -> Unit) {
    if (started) return then()
    waiting = waiting + then
    if (observers != null) return
    if (inForeground()) return uiStarting("a scene was in the foreground when the host started")
    observers = UI_START_NOTIFICATIONS.map { name ->
      // A null queue: the block runs where the notification is posted (UIKit: the main thread).
      center.addObserverForName(name, null, null) { _ -> uiStarting(name ?: "a UI start notification") }
    }
  }

  /** A Keliver screen is being made: this process shows a screen. */
  fun screenShown() = uiStarting("a Keliver screen is being made")

  private fun uiStarting(cause: String) {
    if (started) return
    started = true
    onUiStart(cause)
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

/**
 * Whether a connected scene is in the foreground (inactive or active): the process shows UI.
 *
 * Not `UIApplication.applicationState`, which says nothing this early: in a SwiftUI App's init()
 * there is no shared application yet, so the read answers 0, `active`, whatever the launch
 * (measured on the simulator, scripts/keliver-nf4-ios-sim.sh), and at didFinishLaunching a
 * scene-based app reads `background` in every launch, the user's too. A scene is connected in the foreground only once
 * the process shows UI; a launch in the background connects none there. So a host started in
 * init() waits for the first scene's foreground notification (or its first screen), and a host
 * started later, with its scene already up, starts toward the screen at once. An app without
 * scenes waits for didBecomeActive, or its first Keliver screen.
 */
internal fun sceneInForeground(): Boolean {
  // In a SwiftUI App's init() there is no UIApplication yet (UIApplicationMain has not made it):
  // sharedApplication is nil though declared non-null, and a message to it answers 0 or nil. So a
  // runtime type check, which the compiler cannot fold away, before any use.
  val app: Any? = UIApplication.sharedApplication
  if (app !is UIApplication) return false
  val scenes: Any? = app.connectedScenes
  if (scenes !is Set<*>) return false
  return scenes.any {
    val state = (it as? UIScene)?.activationState
    state == UISceneActivationStateForegroundInactive || state == UISceneActivationStateForegroundActive
  }
}

/**
 * NF4: the host's start, held in a launch that is not toward a screen. `Keliver.start()` is
 * called as early as the App's init() (the template does), which iOS runs for a launch in the
 * background too (a silent push, a background fetch or refresh, a location event). There the
 * bundle lookup, the code load and the declared first requests would spend that launch's small
 * time and memory budget on a screen nobody sees. So [run] starts the host now when the process
 * is starting toward a screen ([UiStartWatch]), and otherwise once it does: a scene or the app
 * entering the foreground, or a Keliver screen being made. A process that never comes to the
 * foreground looks up nothing, loads no code and sends nothing.
 *
 * In the App's init() no scene is connected yet, in any launch, so a launch the user made waits
 * too: until its first scene enters the foreground, about 45 ms before SwiftUI makes the first
 * screen on a simulator (scripts/keliver-nf4-ios-sim.sh's "ui-start" and "open" marks). [log]
 * says so (a debug build logs it).
 *
 * Main thread only, as [UiStartWatch].
 */
internal class StartGate(
  private val uiStart: UiStartWatch,
  private val log: (String) -> Unit,
) {
  private var waiting = false

  /** Runs [start] now, or at the UI start; a call while one waits adds nothing. */
  fun run(start: () -> Unit) {
    if (waiting) return
    waiting = true
    uiStart.whenStarted {
      waiting = false
      start()
    }
    if (waiting) log("start: no scene in the foreground yet; the host starts when one enters it, the app becomes active, or a Keliver screen is made")
  }
}
