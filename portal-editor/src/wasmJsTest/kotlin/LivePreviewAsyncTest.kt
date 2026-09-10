import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.BroadcastFrameClock
import dev.keliver.portal.Bind
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.PreviewBindings
import dev.keliver.portal.render.PreviewFrame
import dev.keliver.portal.render.ScreenPreview
import dev.keliver.portal.render.appPreviewEntry
import dev.keliver.portal.render.strB
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield

/**
 * U19 part 2 — ASYNCHRONOUS presenter updates.
 *
 * [LivePreviewDispatchTest] covers the synchronous route: a preview action
 * writes presenter state inside [LiveEngine.dispatch]. A presenter can also
 * change state with no action at all — a coroutine started by a LaunchedEffect
 * completes, and the state it writes must reach the canvas without the user
 * clicking anything.
 *
 * These tests drive the HOST as well as the guest, so the scheduling connection
 * itself is under test rather than assumed:
 *
 *  - a host Recomposer + composition, whose content reads the same wake signal
 *    the editor's [runPortalEditor] content reads;
 *  - the editor's frame pump — `withFrameNanos { guestClock.sendFrame(it) }` —
 *    as its own coroutine awaiting the host clock;
 *  - a driver that produces a host frame ONLY while the host composition has
 *    pending work, which is the idle-host condition U19 was observed under.
 *
 * The driver never sends a guest frame directly, never calls [HostWakeSignal],
 * and never pumps a fixed number of frames: everything it does is what a
 * browser does anyway (deliver apply-notifications, run a frame when the host
 * has been invalidated). If production does not ask the host for a frame, the
 * driver produces none and the preview stalls — which is the failure.
 *
 * What the assertions read is [rendered]: a composable in the guest composition
 * resolving a [Bind] through [strB], the same accessor RenderNode uses. It is a
 * stand-in for the canvas widget tree, not the canvas itself.
 */
class LivePreviewAsyncTest {

  @AfterTest
  fun cleanUp() {
    LiveEngine.stop()
    appPreviewEntry = null
    PreviewBindings.mocks.clear()
  }

  // ---------------------------------------------------------------- harness

  /** Mirrors the editor's host/guest arrangement closely enough to schedule it. */
  private class Editor {
    private val hostJob = Job()
    private val guestJob = Job()
    val hostClock = BroadcastFrameClock()

    /** The guest clock comes from PRODUCTION — that is the connection under test. */
    val guestClock = newGuestFrameClock()

    private val hostRecomposer = Recomposer(Dispatchers.Unconfined + hostJob + hostClock)
    private val guestRecomposer = Recomposer(Dispatchers.Unconfined + guestJob + guestClock)
    private val hostScope = CoroutineScope(Dispatchers.Unconfined + hostJob + hostClock)
    private val guestScope = CoroutineScope(Dispatchers.Unconfined + guestJob + guestClock)

    private var hostComposition: Composition? = null
    private var guestComposition: Composition? = null
    private var frameTime = 0L

    /** What the preview would draw, most recent last. */
    val rendered = mutableListOf<String>()

    suspend fun start(bindField: String) {
      hostScope.launch { hostRecomposer.runRecomposeAndApplyChanges() }
      guestScope.launch { guestRecomposer.runRecomposeAndApplyChanges() }
      yield()

      // EditorShell's frame pump: one host frame ticks the guest clock once.
      hostScope.launch {
        while (true) {
          androidx.compose.runtime.withFrameNanos { nanos -> guestClock.sendFrame(nanos) }
        }
      }
      yield()

      val host = Composition(UnitApplier2(), hostRecomposer)
      host.setContent {
        // The read runPortalEditor performs, verbatim in effect.
        HostWakeSignal.state.value
      }
      hostComposition = host

      val node = WidgetNode(type = "Text", props = mapOf("text" to Bind(bindField)))
      val guest = Composition(UnitApplier2(), guestRecomposer)
      guest.setContent {
        LivePresenterHost()
        Rendered(node)
      }
      guestComposition = guest
      Snapshot.sendApplyNotifications()
      yield()
      // Startup only. In the editor the host is mounting its own content while
      // Live starts, so frames are already flowing; this stands in for that, and
      // for nothing else. Every later frame in these tests has to be asked for
      // by production code.
      HostWakeSignal.wake()
      runUntilSettled()
    }

    @Composable
    private fun Rendered(node: WidgetNode) {
      val text = node.strB("text")
      androidx.compose.runtime.SideEffect { rendered += text }
    }

    /**
     * Run the browser until nothing more is scheduled, and report how many host
     * frames that took. Zero means the host was never asked for one.
     */
    suspend fun runUntilSettled(maxFrames: Int = 200): Int {
      var frames = 0
      var quiet = 0
      while (frames < maxFrames) {
        Snapshot.sendApplyNotifications()
        yield()
        yield()
        if (hostRecomposer.hasPendingWork) {
          hostClock.sendFrame(frameTime++)
          frames++
          quiet = 0
          yield()
          yield()
        } else {
          // Three quiet turns of the event loop with no host invalidation: a real
          // browser stops calling requestAnimationFrame here.
          if (++quiet >= 3) break
        }
      }
      return frames
    }

    fun dispose() {
      guestComposition?.dispose()
      hostComposition?.dispose()
      guestJob.cancel()
      hostJob.cancel()
    }
  }

  // ------------------------------------------------------------------ cases

  /**
   * Case 1: Live starts on Loading, the host goes idle, then a test-controlled
   * response lands. No click, no selection change, no document edit.
   */
  @Test
  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  fun anAsyncCompletionReachesThePreviewWithNoInteraction(): kotlin.js.Promise<kotlin.js.JsAny?> =
    GlobalScope.promise {
      val gate = CompletableDeferred<String>()
      var presenterState = "?"          // recorded separately from what is displayed
      appPreviewEntry = object : AppPreviewEntry {
        override val label = "test"
        override val screens = mapOf(
          "home" to ScreenPreview {
            var status by remember { mutableStateOf("Loading") }
            LaunchedEffect(Unit) {
              val v = gate.await()
              presenterState = v
              status = v
            }
            PreviewFrame(values = mapOf("status" to status), dispatch = { _, _ -> })
          },
        )
      }

      val editor = Editor()
      LiveEngine.request.value = "home"
      editor.start("status")
      editor.runUntilSettled()
      assertEquals("Loading", editor.rendered.last(), "the first live frame")

      // The host is now idle: nothing is pending, so a browser schedules nothing.
      assertEquals(0, editor.runUntilSettled(), "host must be idle before the response")

      gate.complete("Ready")
      yield()
      assertEquals("Ready", presenterState, "presenter state advanced")

      val frames = editor.runUntilSettled()
      assertTrue(frames > 0, "the completion must ask the host for a frame; it asked for none")
      assertEquals(
        "Ready",
        editor.rendered.last(),
        "an async completion must reach the preview without another interaction",
      )

      editor.dispose()
      null
    }

  /**
   * Case 2: an action starts async work. The dispatch-triggered rendering
   * settles first — so the wake that dispatch performs is already spent — and
   * only then does the response land.
   */
  @Test
  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  fun workStartedByAnActionLandsAfterTheDispatchRenderSettles(): kotlin.js.Promise<kotlin.js.JsAny?> =
    GlobalScope.promise {
      val responses = Channel<String>(Channel.UNLIMITED)
      appPreviewEntry = object : AppPreviewEntry {
        override val label = "test"
        override val screens = mapOf(
          "home" to ScreenPreview {
            var requests by remember { mutableStateOf(0) }
            var status by remember { mutableStateOf("idle") }
            LaunchedEffect(requests) {
              if (requests > 0) {
                status = "loading"
                status = responses.receive()
              }
            }
            PreviewFrame(
              values = mapOf("status" to status),
              dispatch = { action, _ -> if (action == "load") requests += 1 },
            )
          },
        )
      }

      val editor = Editor()
      LiveEngine.request.value = "home"
      editor.start("status")
      editor.runUntilSettled()
      assertEquals("idle", editor.rendered.last())

      LiveEngine.dispatch("load", null)
      editor.runUntilSettled()
      assertEquals("loading", editor.rendered.last(), "the action's own rendering")
      assertEquals(0, editor.runUntilSettled(), "the dispatch wake is spent; the host is idle again")

      responses.send("loaded 42")
      yield()
      editor.runUntilSettled()
      assertEquals(
        "loaded 42",
        editor.rendered.last(),
        "a response that arrives after the action's rendering settled must still show",
      )

      editor.dispose()
      null
    }

  /**
   * Case 3: the session boundaries must dispose pending work. Stopping Live,
   * and switching screen, both end a session; a response that was already in
   * flight when that happened must never be delivered or displayed.
   *
   * Each session gets its OWN response channel. Sharing one buffered channel
   * across sessions would hand session 1's queued message to session 2 — a
   * property of the fixture's queue, not of the editor.
   */
  @Test
  @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
  fun pendingWorkFromAnEndedSessionIsNeverDelivered(): kotlin.js.Promise<kotlin.js.JsAny?> =
    GlobalScope.promise {
      var responses = Channel<String>(Channel.UNLIMITED)
      var deliveries = 0
      appPreviewEntry = object : AppPreviewEntry {
        override val label = "test"
        override val screens = mapOf(
          "home" to ScreenPreview {
            var status by remember { mutableStateOf("idle") }
            LaunchedEffect(Unit) {
              status = "loading"
              val v = responses.receive()
              deliveries += 1
              status = v
            }
            PreviewFrame(values = mapOf("status" to status), dispatch = { _, _ -> })
          },
          "other" to ScreenPreview {
            PreviewFrame(values = mapOf("status" to "other screen"), dispatch = { _, _ -> })
          },
        )
      }

      val editor = Editor()
      LiveEngine.request.value = "home"
      editor.start("status")
      assertEquals("loading", editor.rendered.last(), "the presenter's own async start reached the preview")

      // 1. stop() ends the session while the response is still in flight.
      val session1 = responses
      LiveEngine.stop()
      editor.runUntilSettled()
      assertEquals(null, PreviewBindings.mocks["status"], "stop() clears the live values")
      session1.send("late from session 1")
      yield()
      editor.runUntilSettled()
      assertEquals(0, deliveries, "stop() must cancel the presenter's pending work")

      // 2. a restarted session is fresh, and takes only its own response.
      responses = Channel(Channel.UNLIMITED)
      val session2 = responses
      LiveEngine.request.value = "home"
      editor.runUntilSettled()
      assertEquals("loading", editor.rendered.last(), "a restarted session starts fresh")
      session2.send("session 2")
      yield()
      editor.runUntilSettled()
      assertEquals("session 2", editor.rendered.last())
      assertEquals(1, deliveries, "only the live session's response was delivered")

      // 3. switching screen ends the session the same way, mid-flight.
      responses = Channel(Channel.UNLIMITED)
      val session3 = responses
      LiveEngine.request.value = "other"
      editor.runUntilSettled()
      assertEquals("other screen", editor.rendered.last())
      LiveEngine.request.value = "home"
      editor.runUntilSettled()
      assertEquals("loading", editor.rendered.last(), "session 3 is waiting on its response")
      LiveEngine.request.value = "other"          // ends session 3 while it waits
      editor.runUntilSettled()
      session3.send("late from session 3")
      yield()
      editor.runUntilSettled()
      assertEquals(1, deliveries, "a screen switch must cancel the pending work it disposes")
      assertEquals("other screen", editor.rendered.last(), "a screen that ended must not be written into")

      assertTrue(
        editor.rendered.none { it.startsWith("late from") },
        "an ended session's response must never be displayed: ${editor.rendered}",
      )

      editor.dispose()
      null
    }
}

/** Minimal applier: these tests assert on resolved bindings, not on a widget tree. */
private class UnitApplier2 : androidx.compose.runtime.AbstractApplier<Unit>(Unit) {
  override fun insertBottomUp(index: Int, instance: Unit) {}
  override fun insertTopDown(index: Int, instance: Unit) {}
  override fun move(from: Int, to: Int, count: Int) {}
  override fun remove(index: Int, count: Int) {}
  override fun onClear() {}
}
