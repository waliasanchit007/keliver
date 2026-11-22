package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UISceneActivationStateBackground
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UISceneActivationStateForegroundInactive
import platform.UIKit.UISceneActivationStateUnattached
import platform.UIKit.UISceneWillEnterForegroundNotification
import platform.UIKit.UIWindowSceneSessionRoleApplication

/**
 * NF4 on iOS: `Keliver.start()` called early (the template calls it in the App's init()) starts
 * the host at once in a launch toward a screen, and holds it in a launch in the background (a
 * silent push, a background refresh, a scene connected in the background) until the process comes
 * to the foreground or makes a Keliver screen while a scene is in the foreground (UiStart.kt:
 * StartGate, UiStartWatch, showsUi). On the simulator:
 * `./gradlew -p host-ios iosSimulatorArm64Test`.
 */
class StartGateTest {
  private val center = NSNotificationCenter()
  private val logged = mutableListOf<String>()
  private val causes = mutableListOf<String>()
  private var starts = 0
  /** What [sceneInForeground] answers now. */
  private var foreground = false

  private fun gate(inForeground: Boolean): Pair<StartGate, UiStartWatch> {
    foreground = inForeground
    val watch = UiStartWatch(inForeground = { foreground }, center = center, onUiStart = { causes += it })
    return StartGate(watch, log = { logged += it }) to watch
  }

  @Test fun noSharedApplicationIsNoSceneInTheForeground() {
    // The test binary runs without UIApplicationMain, as a SwiftUI App's init() does: there is no
    // shared application yet. The read must answer false, not end the process.
    assertEquals(false, sceneInForeground())
    // And so the default watch waits there, rather than taking the launch for the user's.
    val center = NSNotificationCenter()
    var ran = 0
    UiStartWatch(center = center).whenStarted { ran++ }
    assertEquals(0, ran)
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    assertEquals(1, ran)
  }

  @Test fun aLaunchTowardAScreenStartsAtOnce() {
    val (gate, _) = gate(inForeground = true)
    gate.run { starts++ }
    assertEquals(1, starts)
    assertEquals(emptyList(), logged)
    assertEquals(listOf("a scene was in the foreground when the host started"), causes)
  }

  @Test fun aBackgroundLaunchLoadsNothingUntilItComesToTheForeground() {
    val (gate, _) = gate(inForeground = false)
    gate.run { starts++ }
    gate.run { starts++ } // start() again while it waits: still one wait
    assertEquals(0, starts)
    assertEquals(1, logged.size, logged.toString())
    assertEquals(true, logged.single().startsWith("start: no scene in the foreground yet"))
    // Other notifications are not a UI start.
    center.postNotificationName("SomethingElse", null)
    assertEquals(0, starts)
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    assertEquals(1, starts)
    assertEquals(listOf(UISceneWillEnterForegroundNotification), causes)
    // Later foregrounds start nothing more.
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    center.postNotificationName(UIApplicationDidBecomeActiveNotification, null)
    assertEquals(1, starts)
    assertEquals(1, causes.size)
  }

  @Test fun eachUiStartNotificationStartsAHeldHost() {
    for (name in UiStartWatch.UI_START_NOTIFICATIONS) {
      val center = NSNotificationCenter()
      var started = 0
      val gate = StartGate(UiStartWatch(inForeground = { false }, center = center), log = {})
      gate.run { started++ }
      center.postNotificationName(name, null)
      assertEquals(1, started, name)
    }
  }

  @Test fun aKeliverScreenMadeWhileASceneIsInTheForegroundStartsAHeldHost() {
    val (gate, watch) = gate(inForeground = false)
    gate.run { starts++ }
    foreground = true // the scene came forward; its notification has not reached the watch yet
    watch.screenShown()
    assertEquals(1, starts)
    assertEquals(listOf("a Keliver screen is being made"), causes)
  }

  @Test fun aKeliverScreenMadeWhileNoSceneIsInTheForegroundDoesNotStartTheHost() {
    // iOS connected a scene in the background (a location event), and SwiftUI built its root view,
    // the template's Keliver screen: nobody sees it, so nothing is looked up, loaded or sent.
    val (gate, watch) = gate(inForeground = false)
    gate.run { starts++ }
    watch.screenShown()
    watch.screenShown()
    assertEquals(0, starts)
    assertEquals(emptyList(), causes)
    // The user opens the app in that process: the foreground starts it, once.
    foreground = true
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    assertEquals(1, starts)
    assertEquals(listOf(UISceneWillEnterForegroundNotification), causes)
    watch.screenShown()
    center.postNotificationName(UIApplicationDidBecomeActiveNotification, null)
    assertEquals(1, starts)
    assertEquals(1, causes.size)
  }

  @Test fun aKeliverScreenInABackgroundSceneBeforeStartLeavesItToTheForeground() {
    // No start() in init(): the background scene's screen calls start() itself (its LaunchedEffect).
    val (gate, watch) = gate(inForeground = false)
    watch.screenShown()
    gate.run { starts++ }
    assertEquals(0, starts)
    center.postNotificationName(UIApplicationDidBecomeActiveNotification, null)
    assertEquals(1, starts)
  }

  @Test fun onlyTheAppsOwnWindowScenesInTheForegroundShowUi() {
    val app = UIWindowSceneSessionRoleApplication
    var activeRead = 0
    val active = { activeRead++; true }
    assertEquals(true, showsUi(listOf(SceneSeen(app, UISceneActivationStateForegroundActive)), active))
    assertEquals(true, showsUi(listOf(SceneSeen(app, UISceneActivationStateForegroundInactive)), active))
    assertEquals(false, showsUi(listOf(SceneSeen(app, UISceneActivationStateBackground)), active))
    assertEquals(false, showsUi(listOf(SceneSeen(app, UISceneActivationStateUnattached)), active))
    // A CarPlay scene is not a window scene (no role here); an external display's has its own role.
    assertEquals(false, showsUi(listOf(SceneSeen(null, UISceneActivationStateForegroundActive)), active))
    val display = "UIWindowSceneSessionRoleExternalDisplayNonInteractive"
    assertEquals(false, showsUi(listOf(SceneSeen(display, UISceneActivationStateForegroundActive)), active))
    // With the phone locked and CarPlay up, the app's own scene is in the background.
    assertEquals(
      false,
      showsUi(listOf(SceneSeen(null, UISceneActivationStateForegroundActive), SceneSeen(app, UISceneActivationStateBackground)), active),
    )
    // iPad multiwindow: any one of the app's scenes in the foreground.
    assertEquals(
      true,
      showsUi(listOf(SceneSeen(app, UISceneActivationStateBackground), SceneSeen(app, UISceneActivationStateForegroundInactive)), active),
    )
    // With scenes connected, the application state is not read.
    assertEquals(0, activeRead)
  }

  @Test fun anAppWithoutScenesShowsUiWhenTheApplicationIsActive() {
    assertEquals(true, showsUi(emptyList(), appActive = { true }))
    assertEquals(false, showsUi(emptyList(), appActive = { false }))
  }

  @Test fun aBackgroundProcessThatNeverComesForwardNeverStarts() {
    val (gate, _) = gate(inForeground = false)
    gate.run { starts++ }
    assertEquals(0, starts)
    assertEquals(emptyList(), causes)
  }

  @Test fun aStartRetriedAfterTheUiStartRunsAtOnce() {
    // "No bundle" resets the host's started flag, and the next screen calls start() again.
    val (gate, watch) = gate(inForeground = false)
    gate.run { starts++ }
    foreground = true
    watch.screenShown()
    gate.run { starts++ }
    assertEquals(2, starts)
    assertEquals(1, logged.size) // only the first, held, start said it waits
  }

  @Test fun theLaunchsOwnForegroundIsNoResume() {
    // The host's own order: start() in init() is held; the first scene's foreground starts it, and
    // the start watches for returns (on-resume updates). UIKit then posts the application's
    // foreground for the same launch: no check (it used to log "update check skipped").
    val (gate, _) = gate(inForeground = false)
    var returns = 0
    val foregroundReturns = ForegroundReturns(center) { returns++ }
    gate.run {
      starts++
      foregroundReturns.watch()
    }
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    center.postNotificationName(UIApplicationWillEnterForegroundNotification, null)
    center.postNotificationName(UIApplicationDidBecomeActiveNotification, null)
    assertEquals(1, starts)
    assertEquals(0, returns)
    // The user leaves and comes back: a return, once per return.
    center.postNotificationName(UIApplicationDidEnterBackgroundNotification, null)
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    center.postNotificationName(UIApplicationWillEnterForegroundNotification, null)
    assertEquals(1, returns)
    center.postNotificationName(UIApplicationWillEnterForegroundNotification, null)
    assertEquals(1, returns)
    center.postNotificationName(UIApplicationDidEnterBackgroundNotification, null)
    center.postNotificationName(UIApplicationWillEnterForegroundNotification, null)
    assertEquals(2, returns)
  }

  @Test fun aHostStartedInTheForegroundChecksAtTheFirstReturn() {
    // Started late (a screen, with its scene already up): no launch foreground is still to come.
    var returns = 0
    val foregroundReturns = ForegroundReturns(center) { returns++ }
    foregroundReturns.watch()
    foregroundReturns.watch() // a start retried after "no bundle": still one watch, one check per return
    center.postNotificationName(UIApplicationDidEnterBackgroundNotification, null)
    center.postNotificationName(UIApplicationWillEnterForegroundNotification, null)
    assertEquals(1, returns)
  }

  @Test fun theDeclaredRequestsWaitBehindAHeldStartAndGoAtTheSameUiStart() {
    // The host's own order: the gate first (start()), then, inside the start, the prefetch's watch.
    val (gate, watch) = gate(inForeground = false)
    val order = mutableListOf<String>()
    gate.run {
      order += "start"
      watch.whenStarted { order += "prefetch may go" }
    }
    assertEquals(emptyList(), order)
    center.postNotificationName(UISceneWillEnterForegroundNotification, null)
    assertEquals(listOf("start", "prefetch may go"), order)
  }
}
