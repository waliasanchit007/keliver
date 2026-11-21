package @@PACKAGE@@

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UISceneWillEnterForegroundNotification

/**
 * NF4 on iOS: `Keliver.start()` called early (the template calls it in the App's init()) starts
 * the host at once in a launch toward a screen, and holds it in a launch in the background (a
 * silent push, a background refresh) until the process comes to the foreground or shows a
 * Keliver screen (UiStart.kt: StartGate, UiStartWatch). On the simulator:
 * `./gradlew -p host-ios iosSimulatorArm64Test`.
 */
class StartGateTest {
  private val center = NSNotificationCenter()
  private val logged = mutableListOf<String>()
  private val causes = mutableListOf<String>()
  private var starts = 0

  private fun gate(inForeground: Boolean): Pair<StartGate, UiStartWatch> {
    val watch = UiStartWatch(inForeground = { inForeground }, center = center, onUiStart = { causes += it })
    return StartGate(watch, log = { logged += it }) to watch
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

  @Test fun aKeliverScreenStartsAHeldHost() {
    val (gate, watch) = gate(inForeground = false)
    gate.run { starts++ }
    watch.screenShown()
    assertEquals(1, starts)
    assertEquals(listOf("a Keliver screen is being made"), causes)
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
    watch.screenShown()
    gate.run { starts++ }
    assertEquals(2, starts)
    assertEquals(1, logged.size) // only the first, held, start said it waits
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
