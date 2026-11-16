/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:OptIn(RedwoodCodegenApi::class)

package dev.keliver.compose.gatea.profile

import androidx.compose.runtime.BroadcastFrameClock
import dev.keliver.RedwoodCodegenApi
import dev.keliver.compose.RedwoodComposition
import dev.keliver.compose.gatea.protocol.guest.KeliverComposeGateAProtocolWidgetSystemFactory
import dev.keliver.protocol.Event
import dev.keliver.protocol.EventTag
import dev.keliver.protocol.Id
import dev.keliver.protocol.guest.ProtocolRedwoodComposition
import dev.keliver.protocol.guest.guestRedwoodVersion
import dev.keliver.sample.threeway.RetainedThreewayStack
import dev.keliver.sample.threeway.ThreewayApp
import dev.keliver.sample.threeway.data.Item
import dev.keliver.sample.threeway.data.ItemsPage
import dev.keliver.sample.threeway.data.ThreewayApi
import dev.keliver.sample.threeway.data.ThreewayMarks
import dev.keliver.ui.Cancellable
import dev.keliver.ui.OnBackPressedCallback
import dev.keliver.ui.OnBackPressedDispatcher
import dev.keliver.ui.UiConfiguration
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/*
 * The guest profile's driver (docs/GUEST_PROFILE.md). It composes the three-way benchmark's screens
 * (ThreewayScreens.kt, unchanged) headlessly:
 *
 *  REAL: the JetBrains Compose runtime (Recomposer, slot table, snapshots), keliver-compose's
 *        RedwoodComposition and node applier, the generated Gate A guest API (Text, Row, LazyColumn…)
 *        and its generated protocol widgets (keliver-compose-gatea-protocol-guest), the lazy list's
 *        window (LazyDsl.kt), the mirror types and their codecs, kotlinx.serialization.
 *  COPY: the protocol recorder (ProfileAdapter.kt is FastGuestProtocolAdapter with counters).
 *  STUB: Zipline. No bridge: a change batch is JSON.stringify'd (timed separately) and dropped; the
 *        frame clock is a BroadcastFrameClock this driver ticks; coroutines run on a queue this
 *        driver drains; the data (20 items) is in memory, so no request, no parsing.
 *
 * A step is what happens between a click and the batch: the click goes in as a protocol Event (tag
 * 1000, the generated widgets' modifier-event property, handle 0) on the widget whose modifier has
 * the clickable, then frames are sent until nothing is pending.
 *
 * Entry points: in Node, `main` runs everything and prints one JSON line per mode. In QuickJS (the
 * JVM harness, QuickJsProfile.kt), the harness calls `globalThis.guestProfile(command)` per step.
 */

/** A coroutine dispatcher whose tasks run only when [drain] is called. */
private class ManualDispatcher : CoroutineDispatcher() {
  private val queue = ArrayDeque<Runnable>()
  val isEmpty: Boolean get() = queue.isEmpty()
  override fun dispatch(context: CoroutineContext, block: Runnable) {
    queue.addLast(block)
  }
  fun drain() {
    while (queue.isNotEmpty()) queue.removeFirst().run()
  }
}

private val statuses = listOf("Paid", "Pending", "Shipped", "Refunded", "Cancelled")

/** The fixture's small page, generated: 20 items with the same shape and similar lengths. */
private fun smallPage(count: Int): ItemsPage = ItemsPage(
  seq = 1,
  count = count,
  items = List(count) { i ->
    Item(
      id = 1000 + i,
      title = "Order #${1000 + i} · Ceramic mug set",
      subtitle = "Customer ${i + 1} · ${(i % 7) + 1} items",
      amount = "₹${(i + 1) * 137}.00",
      status = statuses[i % statuses.size],
      address = "${i + 12} Example Road, Sector ${i % 9}, Example City",
      note = "Leave at the door. Call on arrival.",
      reference = "REF-${(i + 1) * 7919}",
    )
  },
)

private object NoMarks : ThreewayMarks {
  override fun now(): Long = 0L
  override fun mark(seq: Int, name: String, atMillis: Long) = Unit
  override fun composed(screen: String, seq: Int) = Unit
}

internal class StepResult(
  val kind: String,
  val ms: Double,
  val stringifyMs: Double,
  val bytes: Int,
  val hash: Int,
  val creates: Int,
  val properties: Int,
  val modifiers: Int,
  val modifierElements: Int,
  val structural: Int,
  val propertyMs: Double,
  val modifierMs: Double,
)

/** One composition of the app, with its own adapter, clock and queue. */
internal class ProfileRun(
  private val encoding: Encoding,
  private val timeCalls: Boolean,
  items: Int,
  /** NF1's retained stack (the guest's `retain` experiment): the list stays composed under the detail. */
  private val retain: Boolean = false,
) {
  private val dispatcher = ManualDispatcher()
  private val clock = BroadcastFrameClock()
  private var frameNanos = 0L
  private val errors = ArrayList<Throwable>()
  private val scope = CoroutineScope(
    dispatcher + clock + Job() + CoroutineExceptionHandler { _, t -> errors += t },
  )
  private var stringifyMs = 0.0
  private var bytes = 0
  private var hash = 0

  val adapter = ProfileAdapter(
    json = Json,
    hostVersion = guestRedwoodVersion,
    widgetSystemFactory = KeliverComposeGateAProtocolWidgetSystemFactory,
    encoding = encoding,
    timeCalls = timeCalls,
    sendChanges = { changes ->
      val start = now()
      val s = JSON.stringify(changes)
      stringifyMs += now() - start
      bytes += s.length
      hash = 31 * hash + s.hashCode()
    },
  )

  private val composition: RedwoodComposition
  private val page = smallPage(items)
  private var screen = "list"

  private val api = object : ThreewayApi {
    // A new page per request (its seq differs, as the fixture's does), with equal items.
    override suspend fun items(size: String, seq: Int): ItemsPage = page.copy(seq = seq, items = page.items.toList())
  }

  init {
    composition = ProtocolRedwoodComposition(
      scope = scope,
      guestAdapter = adapter,
      widgetVersion = 1U,
      onBackPressedDispatcher = object : OnBackPressedDispatcher {
        override fun addCallback(onBackPressedCallback: OnBackPressedCallback): Cancellable =
          object : Cancellable {
            override fun cancel() = Unit
          }
      },
      saveableStateRegistry = null,
      uiConfigurations = MutableStateFlow(UiConfiguration()),
    )
  }

  private fun reset() {
    adapter.stats.reset()
    stringifyMs = 0.0
    bytes = 0
    hash = 0
  }

  private fun settle() {
    var rounds = 0
    while (true) {
      check(rounds++ < 1000) { "the composition did not settle" }
      dispatcher.drain()
      if (clock.hasAwaiters) {
        frameNanos += 16_666_667L
        clock.sendFrame(frameNanos)
        continue
      }
      if (dispatcher.isEmpty) break
    }
    adapter.emitChanges()
    if (errors.isNotEmpty()) throw errors.first()
  }

  private fun result(kind: String, start: Double): StepResult {
    val total = now() - start
    val s = adapter.stats
    return StepResult(
      kind = kind,
      ms = total - stringifyMs,
      stringifyMs = stringifyMs,
      bytes = bytes,
      hash = hash,
      creates = s.creates,
      properties = s.properties,
      modifiers = s.modifiers,
      modifierElements = s.modifierElements,
      structural = s.structural,
      propertyMs = s.propertyMs,
      modifierMs = s.modifierMs,
    )
  }

  /** The first composition: the list, loading, then the data (a LaunchedEffect, then a frame). */
  fun first(): StepResult {
    reset()
    val start = now()
    val stack = if (retain) RetainedThreewayStack else null
    composition.setContent { ThreewayApp(api, NoMarks, stack) }
    settle()
    return result("first", start)
  }

  /** Clicks the [index]th live clickable (in recording order), then runs frames until idle. */
  private fun click(index: Int) {
    val live = adapter.clickables.filter { adapter.isLive(it) }
    val id = live[index]
    adapter.sendEvent(Event(Id(id), EventTag(1000), listOf(JsonPrimitive(0))))
  }

  /** Open: tap row [row] of the list (live clickables: Load 20, Load 1,000, then the rows). */
  fun open(row: Int): StepResult {
    check(screen == "list")
    reset()
    val start = now()
    click(2 + row)
    settle()
    screen = "detail"
    return result("open", start)
  }

  /**
   * Back: the detail's only clickable is its header's Back, the last live one recorded (with
   * [retain], the hidden list's clickables stay live before it).
   */
  fun back(): StepResult {
    check(screen == "detail")
    reset()
    val start = now()
    click(adapter.clickables.count { adapter.isLive(it) } - 1)
    settle()
    screen = "list"
    return result("back", start)
  }

  /**
   * Reload: tap "Load 20" (the first live clickable on the list). The same 20 items come back as
   * a new page, so the list recomposes with nothing new to show (the benchmark's small API call).
   * OFF by default (`reload=1`): here it re-creates every row of the window (81 creates at 10 rows),
   * where the device's split for the same step recorded one property; not explained yet
   * (docs/GUEST_PROFILE.md, "Unverified"), so no conclusion uses it.
   */
  fun reload(): StepResult {
    check(screen == "list")
    reset()
    val start = now()
    click(0)
    settle()
    return result("reload", start)
  }

  fun close() {
    composition.cancel()
    scope.cancel()
    dispatcher.drain()
  }
}

internal fun StepResult.toJson(): String =
  """{"kind":"$kind","ms":${ms.r()},"stringifyMs":${stringifyMs.r()},"bytes":$bytes,"hash":$hash,""" +
    """"creates":$creates,"properties":$properties,"modifiers":$modifiers,"modifierElements":$modifierElements,""" +
    """"structural":$structural,"propertyMs":${propertyMs.r()},"modifierMs":${modifierMs.r()}}"""

private fun Double.r(): String = (kotlin.math.round(this * 1000) / 1000).toString()

internal fun parseArgs(s: String): Map<String, String> =
  s.split(',').map { it.trim() }.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

private fun encodingOf(name: String): Encoding = when (name) {
  "kotlinx", "as-is" -> Encoding.KOTLINX
  "direct" -> Encoding.DIRECT
  "modcache" -> Encoding.DIRECT_MODCACHE
  "none" -> Encoding.NONE
  else -> error("unknown mode $name")
}

private fun median(xs: List<Double>): Double {
  val s = xs.sorted()
  return if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/** Runs [iterations] open/back pairs after [warmup] pairs; one JSON summary. */
internal fun runMode(mode: String, items: Int, warmup: Int, iterations: Int, timeCalls: Boolean, withReload: Boolean = false, retain: Boolean = false): String {
  val run = ProfileRun(encodingOf(mode), timeCalls, items, retain)
  val first = run.first()
  val steps = ArrayList<StepResult>()
  repeat(warmup + iterations) { i ->
    val open = run.open(i % 5)
    val back = run.back()
    val reload = if (withReload) run.reload() else null
    if (i >= warmup) {
      steps += open
      steps += back
      if (reload != null) steps += reload
    }
  }
  run.close()
  fun summary(kind: String): String {
    val xs = steps.filter { it.kind == kind }
    val last = xs.last()
    return """"$kind":{"n":${xs.size},"medianMs":${median(xs.map { it.ms }).r()},""" +
      """"meanMs":${(xs.sumOf { it.ms } / xs.size).r()},"minMs":${xs.minOf { it.ms }.r()},""" +
      """"medianStringifyMs":${median(xs.map { it.stringifyMs }).r()},""" +
      """"medianPropertyMs":${median(xs.map { it.propertyMs }).r()},"medianModifierMs":${median(xs.map { it.modifierMs }).r()},""" +
      """"hashes":${xs.map { it.hash }.distinct().size},"last":${last.toJson()}}"""
  }
  return """{"mode":"$mode","items":$items,"retain":$retain,"warmup":$warmup,"iterations":$iterations,"timeCalls":$timeCalls,""" +
    """"first":${first.toJson()},${summary("open")},${summary("back")}""" +
    (if (withReload) ",${summary("reload")}}" else "}")
}

/** The QuickJS harness's per-step entry: `init:<args>`, `first`, `open:<row>`, `back`, `close`. */
private var current: ProfileRun? = null

private fun command(c: String): String {
  val name = c.substringBefore(':')
  val arg = c.substringAfter(':', "")
  return when (name) {
    "init" -> {
      val a = parseArgs(arg)
      current?.close()
      current = ProfileRun(encodingOf(a["mode"] ?: "kotlinx"), a["timeCalls"] == "1", (a["items"] ?: "20").toInt(), a["retain"] == "1")
      "{}"
    }
    "first" -> current!!.first().toJson()
    "open" -> current!!.open(arg.toInt()).toJson()
    "back" -> current!!.back().toJson()
    "reload" -> current!!.reload().toJson()
    "close" -> {
      current?.close()
      current = null
      "{}"
    }
    "run" -> {
      val a = parseArgs(arg)
      runMode(a["mode"] ?: "kotlinx", (a["items"] ?: "20").toInt(), (a["warmup"] ?: "3").toInt(), (a["iterations"] ?: "30").toInt(), a["timeCalls"] == "1", retain = a["retain"] == "1")
    }
    else -> error("unknown command $c")
  }
}

fun main() {
  val global: dynamic = js("globalThis")
  global.guestProfile = { c: String -> command(c) }
  val isNode = js("typeof process !== 'undefined' && process.argv !== undefined").unsafeCast<Boolean>()
  if (!isNode) return
  val argv: Array<String> = js("process.argv").unsafeCast<Array<String>>()
  val a = parseArgs(argv.drop(2).joinToString(","))
  val modes = (a["modes"] ?: "kotlinx").split('+')
  val items = (a["items"] ?: "20").toInt()
  val warmup = (a["warmup"] ?: "3").toInt()
  val iterations = (a["iterations"] ?: "30").toInt()
  for (mode in modes) {
    println("GUEST_PROFILE " + runMode(mode, items, warmup, iterations, a["timeCalls"] == "1", a["reload"] == "1", a["retain"] == "1"))
  }
}
