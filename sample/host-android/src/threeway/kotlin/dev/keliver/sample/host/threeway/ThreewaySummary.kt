/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.host.threeway

import kotlin.math.ceil
import org.json.JSONObject

/*
 * The benchmark's own summaries (docs/THREE_WAY_BENCHMARK.md), BENCHMARK-ONLY: logged by the route
 * after each series, so a run needs no analysis script. Medians (the mean of the two middle values
 * for an even count), percentiles by nearest rank (p90 of 12 is the 11th), min and max.
 */

internal fun median(values: List<Double>): Double {
  if (values.isEmpty()) return Double.NaN
  val s = values.sorted()
  val m = s.size / 2
  return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

internal fun nearestRank(values: List<Double>, p: Double): Double {
  if (values.isEmpty()) return Double.NaN
  val s = values.sorted()
  return s[(ceil(p * s.size).toInt() - 1).coerceIn(0, s.size - 1)]
}

/** Rounded to 0.1; NaN (no values) stays NaN, so [orNull] logs it as null, not 0. */
private fun round1(x: Double): Double = if (x.isNaN()) x else Math.round(x * 10) / 10.0

/** org.json refuses NaN (it threw on a group with no frames after the stop): no values = null. */
private fun orNull(x: Double?): Any = if (x == null || x.isNaN()) JSONObject.NULL else x

private fun JSONObject.numbers(): Map<String, Double> {
  val out = LinkedHashMap<String, Double>()
  for (key in keys()) {
    val v = opt(key)
    if (v is Number) out[key] = v.toDouble()
  }
  return out
}

/** One step's iterations (warm-up already dropped): `drawn` as median, p90, min–max; every numeric field's median. */
internal fun summarizeSteps(label: String, results: List<JSONObject>): JSONObject {
  val drawn = results.mapNotNull { it.optDouble("drawn").takeUnless(Double::isNaN) }
  val keys = LinkedHashSet<String>()
  results.forEach { keys += it.numbers().keys }
  val medians = JSONObject()
  for (key in keys) {
    if (key == "i" || key == "seq") continue
    medians.put(key, orNull(round1(median(results.mapNotNull { it.numbers()[key] }))))
  }
  return JSONObject()
    .put("summary", label)
    .put("n", results.size)
    .put("drawn", JSONObject().put("median", orNull(round1(median(drawn)))).put("p90", orNull(nearestRank(drawn, 0.9))).put("min", orNull(drawn.minOrNull())).put("max", orNull(drawn.maxOrNull())))
    .put("medians", medians)
}

/**
 * A group of flings (one direction, one speed). Flings that are not a clean fling are counted and
 * left out: closed by the next touch (`closedBy` = `touch`: their guest work may run into the next
 * fling), with a guest frame still running at their DOWN, or that hit an end of the list (`edge`).
 * Over the rest: missed vsyncs (the jank measure), frame percentiles and shares over the frames
 * while the list moved (`movingFrames`; `TOTAL_DURATION` depends on where a frame starts against
 * vsync, so these are kept for comparison with the first session, not as jank), the frames after
 * it stopped apart, and per-fling medians. Compare forms on times and px, not on row counts.
 */
internal fun summarizeFlings(label: String, all: List<JSONObject>): JSONObject {
  val flings = all.filterNot { it.optString("closedBy") == "touch" || it.optBoolean("guestBusyAtDown") || it.optBoolean("edge") }
  fun arr(f: JSONObject, key: String) = f.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList()
  val frames = flings.flatMap { arr(it, "movingFrames") }
  val afterStop = flings.flatMap { arr(it, "afterStopFrames") }
  fun share(limit: Double) = orNull(if (frames.isEmpty()) Double.NaN else round1(100.0 * frames.count { it > limit } / frames.size))
  fun med(key: String) = orNull(round1(median(flings.mapNotNull { f -> f.opt(key).let { if (it is Number) it.toDouble() else null } })))
  fun p90(key: String) = orNull(nearestRank(flings.mapNotNull { (it.opt(key) as? Number)?.toDouble() }, 0.9))
  fun rank(values: List<Double>, p: Double) = orNull(nearestRank(values, p))
  fun sum(key: String) = flings.sumOf { it.optDouble(key, 0.0) }
  val o = JSONObject()
    .put("summary", label)
    .put("flings", flings.size)
    .put("excludedTouch", all.count { it.optString("closedBy") == "touch" })
    .put("excludedGuestBusyAtDown", all.count { it.optBoolean("guestBusyAtDown") })
    .put("excludedEdge", all.count { it.optBoolean("edge") })
    .put("upVelocityDpSMedian", med("upVelocityDpS"))
    .put("travelPxMedian", med("travelPx"))
    .put("stopAfterUpMsMedian", med("stopAfterUpMs"))
    .put("missedVsyncsMedian", med("missedVsyncs"))
    .put("missedVsyncsTotal", sum("missedVsyncs"))
    .put("movingFrames", frames.size)
    .put("p50", rank(frames, 0.5)).put("p90", rank(frames, 0.9)).put("p99", rank(frames, 0.99)).put("max", orNull(frames.maxOrNull()))
    .put("over16_7pct", share(16.7)).put("over33_3pct", share(33.3)).put("over100pct", share(100.0))
    .put("afterStopFrames", afterStop.size)
    .put("afterStopP90", rank(afterStop, 0.9)).put("afterStopMax", orNull(afterStop.maxOrNull()))
    .put("tailMsMedian", med("tailMs"))
    .put("framesPerFlingMedian", med("framesCount"))
  if (flings.any { it.has("bFrames") }) {
    o.put("settledAfterUpMsMedian", med("bSettledAfterUpMs"))
      .put("settledAfterUpMsP90", p90("bSettledAfterUpMs"))
      .put("contentAfterUpMsMedian", med("bContentAfterUpMs"))
      .put("lastPlaceholderAfterUpMsMedian", med("bLastBlankAfterUpMs"))
      .put("lastMoveAfterUpMsMedian", med("bLastMoveAfterUpMs"))
      .put("flingsWithPlaceholders", flings.count { it.optInt("bFramesWithBlank") > 0 })
      .put("flingsWithSubRowGapsOnly", flings.count { it.optInt("bFramesWithBlank") == 0 && it.optInt("bFramesWithGap") > 0 })
      .put("framesWithPlaceholders", sum("bFramesWithBlank"))
      .put("placeholderRowFrames", sum("bPlaceholderFrames"))
      .put("maxPlaceholdersOnScreen", flings.maxOfOrNull { it.optInt("bMaxPlaceholders") })
  }
  if (flings.any { it.has("gFrames") }) {
    val gMs = sum("gMs")
    val creates = flings.sumOf { it.optJSONObject("guest")?.optDouble("creates", 0.0) ?: 0.0 }
    o.put("guestFramesMedian", med("gFrames"))
      .put("guestFramesWithChangesMedian", med("gFramesWithChanges"))
      .put("guestMsPerFlingMedian", med("gMs"))
      .put("guestLongestFrameMsMax", flings.maxOfOrNull { it.optDouble("gMaxMs", 0.0) })
      .put("guestWidgetsCreated", creates)
      .put("guestMsPerWidget", if (creates > 0) round1(gMs / creates) else JSONObject.NULL)
      .put("guestMsPerRow", if (creates > 0) round1(gMs / (creates / WIDGETS_PER_ROW)) else JSONObject.NULL)
      .put("guestBusyEndAfterUpMsMedian", med("gLastEndAfterUpMs"))
  }
  return o
}

/** A list row in the guest: its ExtraLazyItem, the row, two columns, a box and four texts. */
private const val WIDGETS_PER_ROW = 9.0
