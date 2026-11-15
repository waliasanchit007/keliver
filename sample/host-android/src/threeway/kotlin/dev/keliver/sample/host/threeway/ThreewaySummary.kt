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

private fun round1(x: Double): Double = Math.round(x * 10) / 10.0

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
    medians.put(key, round1(median(results.mapNotNull { it.numbers()[key] })))
  }
  return JSONObject()
    .put("summary", label)
    .put("n", results.size)
    .put("drawn", JSONObject().put("median", round1(median(drawn))).put("p90", nearestRank(drawn, 0.9)).put("min", drawn.minOrNull()).put("max", drawn.maxOrNull()))
    .put("medians", medians)
}

/** A group of flings (one direction, one speed): frame percentiles and shares over all their frames, and per-fling medians. */
internal fun summarizeFlings(label: String, flings: List<JSONObject>): JSONObject {
  val frames = flings.flatMap { f -> f.optJSONArray("frames")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList() }
  fun share(limit: Double) = if (frames.isEmpty()) Double.NaN else round1(100.0 * frames.count { it > limit } / frames.size)
  fun med(key: String) = round1(median(flings.mapNotNull { f -> f.opt(key).let { if (it is Number) it.toDouble() else null } }))
  fun sum(key: String) = flings.sumOf { it.optDouble(key, 0.0) }
  val o = JSONObject()
    .put("summary", label)
    .put("flings", flings.size)
    .put("frames", frames.size)
    .put("p50", nearestRank(frames, 0.5)).put("p90", nearestRank(frames, 0.9)).put("p99", nearestRank(frames, 0.99)).put("max", frames.maxOrNull())
    .put("over16_7pct", share(16.7)).put("over33_3pct", share(33.3)).put("over100pct", share(100.0))
    .put("tailMsMedian", med("tailMs"))
    .put("framesPerFlingMedian", med("framesCount"))
    .put("distanceMedian", round1(median(flings.mapNotNull { f -> if (f.has("from") && f.has("to")) Math.abs(f.getDouble("to") - f.getDouble("from")) else null })))
  if (flings.any { it.has("bFrames") }) {
    o.put("settledAfterUpMsMedian", med("bSettledAfterUpMs"))
      .put("settledAfterUpMsP90", nearestRank(flings.mapNotNull { it.opt("bSettledAfterUpMs") as? Number }.map { it.toDouble() }, 0.9))
      .put("lastMoveAfterUpMsMedian", med("bLastMoveAfterUpMs"))
      .put("flingsWithPlaceholders", flings.count { it.optInt("bFramesWithBlank") > 0 })
      .put("framesWithPlaceholders", sum("bFramesWithBlank"))
      .put("placeholderRowFrames", sum("bPlaceholderFrames"))
      .put("maxPlaceholdersOnScreen", flings.maxOfOrNull { it.optInt("bMaxPlaceholders") })
      .put("lastPlaceholderAfterUpMsMedian", med("bLastBlankAfterUpMs"))
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
