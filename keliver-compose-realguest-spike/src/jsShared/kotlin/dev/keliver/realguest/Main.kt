/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.realguest

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch

/*
 * Research spike (eff-guest, 2026-11-24): does the REAL Compose Multiplatform value and animation
 * code (ui-unit, ui-geometry, ui-graphics, ui-text, ui, foundation, animation-core at 1.12.2) work in
 * a Zipline guest, in QuickJS? Each variant project links a different subset of the case files
 * below; the JVM harness (harness/) loads the variant's production .zipline modules into the fork's
 * QuickJS and runs every case through `globalThis.realGuest(command)`.
 *
 * Commands: `list` (case names, comma separated); `run:<case>` (one JSON object: ok, result or
 * error); `bench:<case>:<n>` (runs the case's bench body n times and returns "ok", timed by the
 * harness on the JVM clock).
 */

class Case(
  val name: String,
  val bench: ((Int) -> Any?)? = null,
  val body: () -> String,
)

private val cases: Map<String, Case> by lazy { (baseCases() + registeredCases()).associateBy { it.name } }

private var sink: Any? = null

fun command(c: String): String = when {
  c == "list" -> cases.keys.joinToString(",")
  c == "benches" -> cases.values.filter { it.bench != null }.joinToString(",") { it.name }
  c.startsWith("run:") -> runCase(c.removePrefix("run:"))
  c.startsWith("bench:") -> {
    val parts = c.removePrefix("bench:").split(':')
    val case = cases.getValue(parts[0])
    sink = case.bench!!.invoke(parts[1].toInt())
    "ok"
  }
  else -> error("unknown command $c")
}

private fun runCase(name: String): String = try {
  val r = cases.getValue(name).body()
  """{"ok":true,"result":${jsonString(r)}}"""
} catch (t: Throwable) {
  """{"ok":false,"error":${jsonString(t.toString())},"stack":${jsonString(t.stackTraceToString().take(3000))}}"""
}

fun jsonString(s: String): String = buildString {
  append('"')
  for (ch in s) {
    when (ch) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> if (ch < ' ') append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
    }
  }
  append('"')
}

/** A coroutine dispatcher whose tasks run only when [drain] is called (as the guest profile's). */
class ManualDispatcher : CoroutineDispatcher() {
  private val queue = ArrayDeque<Runnable>()
  override fun dispatch(context: CoroutineContext, block: Runnable) {
    queue.addLast(block)
  }
  fun drain() {
    while (queue.isNotEmpty()) queue.removeFirst().run()
  }
}

/** A frame-clock driver: what the guest's host-driven clock does, without a host. */
class Frames {
  val dispatcher = ManualDispatcher()
  val clock = BroadcastFrameClock()
  val job = Job()
  val scope = CoroutineScope(dispatcher + clock + job)
  var nanos = 0L
  var frames = 0

  /** Sends one 16 ms frame and runs what it resumed. */
  fun frame() {
    nanos += 16_000_000L
    frames++
    clock.sendFrame(nanos)
    dispatcher.drain()
  }

  fun launch(block: suspend CoroutineScope.() -> Unit): Job {
    val j = scope.launch(block = block)
    dispatcher.drain()
    return j
  }
}

/** What every guest already has: the Compose runtime's snapshots and frame clock. */
private fun baseCases(): List<Case> = listOf(
  Case("base.snapshot") {
    val s = mutableStateOf(1)
    Snapshot.withMutableSnapshot { s.value = 2 }
    "state=${s.value}"
  },
  Case("base.frameClock") {
    val f = Frames()
    var seen = 0L
    f.launch { seen = androidx.compose.runtime.withFrameNanos { it } }
    f.frame()
    f.job.cancel()
    "frame=$seen"
  },
)

fun main() {
  val g: dynamic = js("globalThis")
  g.realGuest = { c: String -> command(c) }
}
