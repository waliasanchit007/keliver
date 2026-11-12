/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.compose.foundation.lazy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.compose.wire.KeliverGeneratedApi
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The guest's handle on a host-owned `androidx.compose.foundation.lazy.LazyListState`
 * (docs/COMPOSE_GENERATOR_DESIGN.md §10). The host creates the real state where the list renders.
 *
 * - **Guest to host:** [scrollToItem] and [animateScrollToItem] are commands carried in the list's
 *   `state` property ([wire]); the host runs them on the real state. Each suspends for one frame,
 *   not until the host has scrolled (Gate B, part b, decides whether completion is reported).
 * - **Host to guest:** [firstVisibleItemIndex] and [firstVisibleItemScrollOffset] follow the host's
 *   viewport, which the list reports when its first or last visible item changes (the same event
 *   moves the guest's window of composed items). The offset is as of that report, not every frame.
 *
 * Not mirrored: `layoutInfo`, `interactionSource`, `isScrollInProgress`, `requestScrollToItem`.
 */
@Stable
public class LazyListState(
  firstVisibleItemIndex: Int = 0,
  firstVisibleItemScrollOffset: Int = 0,
) {
  private val initialIndex = firstVisibleItemIndex
  private val initialOffset = firstVisibleItemScrollOffset

  /** The index of the first visible item, as the host last reported it. */
  public var firstVisibleItemIndex: Int by mutableIntStateOf(firstVisibleItemIndex)
    private set

  /** The scroll offset of the first visible item, as the host last reported it. */
  public var firstVisibleItemScrollOffset: Int by mutableIntStateOf(firstVisibleItemScrollOffset)
    private set

  /** The index of the last visible item, as the host last reported it (Compose: `layoutInfo`). */
  @KeliverGeneratedApi
  public var lastVisibleItemIndex: Int by mutableIntStateOf(firstVisibleItemIndex)
    private set

  public val canScrollBackward: Boolean get() = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0

  private var command: ScrollCommand? by mutableStateOf(null)

  /**
   * The id of the last command this state issued. A new state starts from a random id and a
   * restored one from its saved id, so neither reuses an id the host has already run: the host
   * keeps the id it ran across its own save and restore, which a guest that was not restored (an
   * Android process death keeps the host's saved state but not the guest's) cannot know.
   */
  private var lastCommandId = Random.nextInt()

  /** The latest programmatic scroll, read by the guest's window of composed items. */
  @KeliverGeneratedApi
  public val pendingScroll: ScrollCommand? get() = command

  /** What the list sends as its `state` property; reading it subscribes to new commands. */
  @KeliverGeneratedApi
  public val wire: LazyListStateWire get() = LazyListStateWire(initialIndex, initialOffset, command)

  public suspend fun scrollToItem(index: Int, scrollOffset: Int = 0) {
    scroll(index, scrollOffset, animated = false)
  }

  public suspend fun animateScrollToItem(index: Int, scrollOffset: Int = 0) {
    scroll(index, scrollOffset, animated = true)
  }

  private suspend fun scroll(index: Int, scrollOffset: Int, animated: Boolean) {
    require(index >= 0) { "Index should be non-negative ($index)" }
    // 0 is never sent: it is the id a list that has not run a command yet holds.
    lastCommandId = (lastCommandId + 1).let { if (it == 0) 1 else it }
    command = ScrollCommand(lastCommandId, index, scrollOffset, animated)
    withFrameNanos { }
  }

  /** Called by the list with the host's viewport. */
  @KeliverGeneratedApi
  public fun onHostViewport(firstIndex: Int, lastIndex: Int, firstOffset: Int) {
    firstVisibleItemIndex = firstIndex
    lastVisibleItemIndex = lastIndex
    firstVisibleItemScrollOffset = firstOffset
  }

  public companion object {
    /**
     * As Compose's `LazyListState.Saver`: saves the position (and the last command's id), so a
     * restored guest starts where the host last reported, without a command to re-run.
     */
    public val Saver: Saver<LazyListState, *> = listSaver(
      save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset, it.lastCommandId) },
      restore = { LazyListState(it[0], it[1]).apply { lastCommandId = it[2] } },
    )
  }
}

/** As `androidx.compose.foundation.lazy.rememberLazyListState`: saved and restored with [LazyListState.Saver]. */
@Composable
public fun rememberLazyListState(
  initialFirstVisibleItemIndex: Int = 0,
  initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState = rememberSaveable(saver = LazyListState.Saver) {
  LazyListState(initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)
}

/**
 * A programmatic scroll; [id] grows by one per command from a random start (never 0), so the host
 * runs each once, even after a restore that kept the host's saved state but not the guest's.
 */
@Immutable
public class ScrollCommand(public val id: Int, public val index: Int, public val offset: Int, public val animated: Boolean) {
  override fun equals(other: Any?): Boolean =
    other is ScrollCommand && other.id == id && other.index == index && other.offset == offset && other.animated == animated
  override fun hashCode(): Int = id
  override fun toString(): String = "ScrollCommand($id, $index, $offset, animated=$animated)"
}

/**
 * The wire value of a [LazyListState]: where the host's state starts, and the latest command.
 * Wire: `{"i":0,"o":0}`, with `"c":[id,index,offset,animated]` once the guest has scrolled.
 */
@Immutable
@Serializable(with = LazyListStateWireCodec::class)
public class LazyListStateWire(public val initialIndex: Int, public val initialOffset: Int, public val command: ScrollCommand?) {
  override fun equals(other: Any?): Boolean =
    other is LazyListStateWire && other.initialIndex == initialIndex && other.initialOffset == initialOffset && other.command == command
  override fun hashCode(): Int = (initialIndex * 31 + initialOffset) * 31 + (command?.hashCode() ?: 0)
  override fun toString(): String = "LazyListStateWire($initialIndex, $initialOffset, $command)"
}

public object LazyListStateWireCodec : JsonCodec<LazyListStateWire>("dev.keliver.compose.LazyListState") {
  override fun toJson(value: LazyListStateWire): JsonElement = buildJsonObject {
    put("i", value.initialIndex)
    put("o", value.initialOffset)
    value.command?.let { c -> put("c", JsonArray(listOf(JsonPrimitive(c.id), JsonPrimitive(c.index), JsonPrimitive(c.offset), JsonPrimitive(c.animated)))) }
  }

  override fun fromJson(json: JsonElement): LazyListStateWire {
    val o = json.jsonObject
    val c = o["c"]?.takeIf { it != JsonNull }?.jsonArray?.let {
      ScrollCommand(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int, it[2].jsonPrimitive.int, it[3].jsonPrimitive.boolean)
    }
    return LazyListStateWire(o.getValue("i").jsonPrimitive.int, o.getValue("o").jsonPrimitive.int, c)
  }
}
