package @@PACKAGE@@

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UISceneActivationState
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UISceneActivationStateForegroundInactive
import platform.UIKit.UISceneWillEnterForegroundNotification
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneSessionRoleApplication
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
 * - a Keliver screen being made ([screenShown]) while a scene is in the foreground.
 *
 * A screen made while no scene is in the foreground is not a UI start: iOS may connect a scene
 * in the background (Apple's example is a location event), and SwiftUI may then build its root
 * view, the template's Keliver screen, with nobody seeing it. That launch waits for the
 * foreground notifications like any other. So a launch whose scenes stay in the background (a
 * push, a background fetch or refresh, a location event) shows nothing and sends nothing, until
 * the user brings the app to the foreground in that process.
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

  /**
   * A Keliver screen is being made: a UI start when a scene is in the foreground. Otherwise (a
   * scene connected in the background builds its root view) nothing: the screen's own start()
   * waits for the foreground notifications ([whenStarted]).
   */
  fun screenShown() {
    if (started || !inForeground()) return
    uiStarting("a Keliver screen is being made")
  }

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
 * Whether the process shows UI now: one of the app's own window scenes is in the foreground
 * (inactive or active).
 *
 * Not `UIApplication.applicationState` alone, which says nothing this early: in a SwiftUI App's
 * init() there is no shared application yet, so the read answers 0, `active`, whatever the launch
 * (measured on the simulator, scripts/keliver-nf4-ios-sim.sh), and at didFinishLaunching a
 * scene-based app reads `background` in every launch, the user's too. A scene is in the
 * foreground only once the process shows UI. So a host started in init() waits for the first
 * scene's foreground notification (or its first screen made in the foreground), and a host
 * started later, with its scene already up, starts toward the screen at once.
 *
 * Only the app's own window scenes count (session role `UIWindowSceneSessionRoleApplication`): a
 * CarPlay scene (a `CPTemplateApplicationScene`, not a window scene) or an external display's
 * scene (another role) can be in the foreground while the phone is locked and the app's own UI is
 * not, and neither shows a Keliver screen. An app without scenes (a shared application with none
 * connected) shows UI when the application is active; until then it waits for didBecomeActive.
 * [showsUi] is the rule, tested without a UIApplication (StartGateTest).
 */
internal fun sceneInForeground(): Boolean {
  // In a SwiftUI App's init() there is no UIApplication yet (UIApplicationMain has not made it):
  // sharedApplication is nil though declared non-null, and a message to it answers 0 or nil. So a
  // runtime type check, which the compiler cannot fold away, before any use.
  val app: Any? = UIApplication.sharedApplication
  if (app !is UIApplication) return false
  val scenes: Any? = app.connectedScenes
  if (scenes !is Set<*>) return false
  return showsUi(
    scenes.map { scene ->
      val window = scene as? UIWindowScene
      SceneSeen(role = window?.session?.role, state = window?.activationState)
    },
    appActive = { app.applicationState == UIApplicationState.UIApplicationStateActive },
  )
}

/** A connected scene as [showsUi] sees it: its session role (null: not a window scene) and state. */
internal class SceneSeen(val role: String?, val state: UISceneActivationState?)

/**
 * [sceneInForeground]'s rule: an application window scene in the foreground; with no scene
 * connected (an app without scenes), the application active ([appActive], read only then).
 */
internal fun showsUi(scenes: List<SceneSeen>, appActive: () -> Boolean): Boolean {
  if (scenes.isEmpty()) return appActive()
  return scenes.any {
    it.role == UIWindowSceneSessionRoleApplication &&
      (it.state == UISceneActivationStateForegroundInactive || it.state == UISceneActivationStateForegroundActive)
  }
}

/**
 * The app coming back to the foreground, for the on-resume update check: [onReturn] runs on each
 * `UIApplicationWillEnterForegroundNotification` that follows a
 * `UIApplicationDidEnterBackgroundNotification` seen since [watch].
 *
 * A foreground with no background before it is the launch's own, not a return. The host watches
 * from its start, and NF4 starts it at the UI start, which is the first scene's
 * `UISceneWillEnterForegroundNotification`: UIKit posts the application's
 * `UIApplicationWillEnterForegroundNotification` right after it, for the same foreground. Taken
 * for a resume, it came 0 s after the start's own lookup and was skipped by the 30 s throttle (a
 * "skipped" line in every launch); it is not a check to make at all. A host started later, with
 * its scene already up, sees no launch foreground: the first one after a background is the user's
 * return either way.
 *
 * Main thread only: UIKit posts these notifications on it.
 */
internal class ForegroundReturns(
  private val center: NSNotificationCenter = NSNotificationCenter.defaultCenter,
  private val onReturn: () -> Unit,
) {
  private var wentToBackground = false
  private var observers: List<NSObjectProtocol>? = null

  /** Starts watching; a second call adds nothing. */
  fun watch() {
    if (observers != null) return
    observers = listOf(
      // A null queue: the block runs where the notification is posted (UIKit: the main thread).
      center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ -> wentToBackground = true },
      center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, null) { _ ->
        if (wentToBackground) {
          wentToBackground = false
          onReturn()
        }
      },
    )
  }
}

/**
 * NF4: the host's start, held in a launch that is not toward a screen. `Keliver.start()` is
 * called as early as the App's init() (the template does), which iOS runs for a launch in the
 * background too (a silent push, a background fetch or refresh, a location event). There the
 * bundle lookup, the code load and the declared first requests would spend that launch's small
 * time and memory budget on a screen nobody sees. So [run] starts the host now when the process
 * is starting toward a screen ([UiStartWatch]), and otherwise once it does: a scene or the app
 * entering the foreground, or a Keliver screen being made while a scene is in the foreground. A
 * process whose scenes stay in the background looks up nothing, loads no code and sends nothing.
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
    if (waiting) log("start: no scene in the foreground yet; the host starts when one enters it, the app becomes active, or a Keliver screen is made while one is")
  }
}
