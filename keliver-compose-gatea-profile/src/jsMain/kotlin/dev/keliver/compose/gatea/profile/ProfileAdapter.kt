/*
 * Copyright (C) 2024 Square, Inc.
 * Copyright (C) 2026 Keliver contributors.
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
package dev.keliver.compose.gatea.profile

import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.compose.gatea.modifier.GenClickable1
import dev.keliver.compose.gatea.modifier.GenClickable2
import dev.keliver.compose.wire.JsonCodec
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChangesSink
import dev.keliver.protocol.ChildrenTag
import dev.keliver.protocol.Event
import dev.keliver.protocol.Id
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.RedwoodVersion
import dev.keliver.protocol.WidgetTag
import dev.keliver.protocol.guest.GuestProtocolAdapter
import dev.keliver.protocol.guest.ProtocolMismatchHandler
import dev.keliver.protocol.guest.ProtocolWidget
import dev.keliver.protocol.guest.ProtocolWidget.Companion.INVALID_INDEX
import dev.keliver.protocol.guest.ProtocolWidgetChildren
import dev.keliver.protocol.guest.ProtocolWidgetSystemFactory
import dev.keliver.widget.WidgetSystem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.encodeToDynamic

/** How [ProfileAdapter] encodes property and modifier values. */
/** `@Widget(14) GenLazyColumn1` in keliver-compose-gatea-schema; its viewport event is `@Property(13)`. */
internal const val LAZY_COLUMN_TAG = 14
internal const val LAZY_VIEWPORT_TAG = 13

/**
 * NF3: a list row's modifier-event handles. Its chain has the clickable (handle 0) and then
 * `prepareOnPress`'s `ExtraPress` (press 1, cancel 2), as ThreewayScreens.kt's ItemRow orders them.
 */
internal const val PRESS_HANDLE = 1
internal const val CANCEL_HANDLE = 2

internal enum class Encoding {
  /** As shipped: `json.encodeToDynamic(serializer, value)` for every value (treehouse-guest's FastGuestProtocolAdapter). */
  KOTLINX,

  /**
   * Suspect 2 (docs/GUEST_PROFILE.md): String, Int, Float and Double are pushed as they are; a mirror
   * type's [JsonCodec] builds its JsonElement once, and a hand-written walk turns that into JS values
   * (no second generic pass through `encodeToDynamic`). Everything else (modifier elements, enums,
   * nullable wrappers) as shipped. Must produce the same batches as [KOTLINX]: ProfileRunTest checks
   * the first steps, and the QuickJS harness (QuickJsProfile.kt) compares every step's batch hash and
   * byte count across the modes of one run and fails if any differs.
   */
  DIRECT,

  /**
   * [DIRECT], and each modifier element's encoded value is cached by the element's equality (the
   * same `padding(16.dp)` on every row is encoded once): a bound on what a direct modifier encoder
   * could save, not a design (the cache never evicts).
   */
  DIRECT_MODCACHE,

  /** No values at all (structure only): the differential baseline for "recording costs X". */
  NONE,
}

/**
 * A copy of keliver-treehouse-guest's `FastGuestProtocolAdapter` (src/jsMain/.../ProtocolBridgeJs.kt,
 * which is internal), the recorder the guest uses on JS. Differences, all for measurement:
 *  - the batch goes to [sendChanges] (here: `JSON.stringify`, a stand-in for the bridge) instead of
 *    the Zipline service;
 *  - [encoding] selects how values are encoded;
 *  - it counts and times the recording calls ([stats]) and remembers which widgets carry a
 *    clickable modifier, so the driver can click them through [sendEvent] as a host would.
 */
@OptIn(ExperimentalSerializationApi::class, RedwoodCodegenApi::class)
internal class ProfileAdapter(
  override val json: Json = Json.Default,
  hostVersion: RedwoodVersion,
  private val widgetSystemFactory: ProtocolWidgetSystemFactory,
  private val mismatchHandler: ProtocolMismatchHandler = ProtocolMismatchHandler.Throwing,
  private val encoding: Encoding,
  private val timeCalls: Boolean,
  private val sendChanges: (changes: dynamic) -> Unit,
) : GuestProtocolAdapter(hostVersion) {
  private var nextValue = Id.Root.value + 1
  private val widgets = JsMap<Int, ProtocolWidget>()
  private val removed = JsSet<Int>()
  private val changes = JsArray<Change>()

  val stats = Stats()
  private val modifierCache = HashMap<Modifier.Element, dynamic>()

  /** Widgets whose modifier chain had a clickable element, in the order they were recorded. */
  val clickables = ArrayList<Int>()

  /** Generated `LazyColumn` widgets (GenLazyColumn1), in the order they were created (NF3's viewport report). */
  val lazyColumns = ArrayList<Int>()

  override val widgetSystem: WidgetSystem<Unit> =
    widgetSystemFactory.create(this, mismatchHandler)

  override val root: ProtocolWidgetChildren =
    ProtocolWidgetChildren(Id.Root, ChildrenTag.Root, this)

  fun isLive(id: Int): Boolean = widgets.has(id)

  /** The recorder's own retained state, for the memory-growth readings (docs/GUEST_PROFILE.md). */
  fun counts(): String =
    """{"widgets":${widgets.size},"removed":${removed.size},"clickables":${clickables.size},""" +
      """"liveClickables":${clickables.count { isLive(it) }},"modifierCache":${modifierCache.size},"nextId":$nextValue}"""

  override fun sendEvent(event: Event) {
    val node = widgets[event.id.value]
    if (node != null) {
      node.sendEvent(event)
    } else {
      mismatchHandler.onUnknownEventNode(event.id, event.tag)
    }
  }

  override fun initChangesSink(changesSink: ChangesSink) = Unit

  override fun nextId(): Id {
    val value = nextValue
    nextValue = value + 1
    return Id(value)
  }

  override fun appendCreate(
    id: Id,
    tag: WidgetTag,
  ) {
    stats.creates++
    if (tag.value == LAZY_COLUMN_TAG) lazyColumns += id.value
    val id = id
    val tag = tag
    changes.push(js("""["create",{"id":id,"tag":tag}]"""))
  }

  override fun <T> appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    serializer: KSerializer<T>,
    value: T,
  ) {
    stats.properties++
    if (encoding == Encoding.NONE) return
    val start = if (timeCalls) now() else 0.0
    val id = id
    val widget = widgetTag
    val tag = propertyTag
    val encodedValue = when (encoding) {
      Encoding.DIRECT, Encoding.DIRECT_MODCACHE -> value?.let { encodeDirect(serializer, it) }
      else -> value?.let { json.encodeToDynamic(serializer, it) }
    }
    changes.push(js("""["property",{"id":id,"widget":widget,"tag":tag,"value":encodedValue}]"""))
    if (timeCalls) stats.propertyMs += now() - start
  }

  private fun <T> encodeDirect(serializer: KSerializer<T>, value: T): dynamic = when {
    value is String -> value
    value is Int -> value
    value is Double -> value
    value is Float -> value
    serializer is JsonCodec<*> -> {
      @Suppress("UNCHECKED_CAST")
      toDynamic((serializer as JsonCodec<T>).toJson(value))
    }
    else -> json.encodeToDynamic(serializer, value)
  }

  override fun appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    value: Boolean,
  ) {
    stats.properties++
    if (encoding == Encoding.NONE) return
    val id = id
    val widget = widgetTag
    val tag = propertyTag
    val value = value
    changes.push(js("""["property",{"id":id,"widget":widget,"tag":tag,"value":value}]"""))
  }

  override fun appendPropertyChange(
    id: Id,
    widgetTag: WidgetTag,
    propertyTag: PropertyTag,
    value: UInt,
  ) {
    stats.properties++
    if (encoding == Encoding.NONE) return
    val id = id
    val widget = widgetTag
    val tag = propertyTag
    val value = value.toDouble()
    changes.push(js("""["property",{"id":id,"widget":widget,"tag":tag,"value":value}]"""))
  }

  override fun appendModifierChange(id: Id, value: Modifier) {
    stats.modifiers++
    val start = if (timeCalls) now() else 0.0
    val elements = js("[]")
    var clickable = false

    value.forEach { element ->
      stats.modifierElements++
      val (tag, serializer) = widgetSystemFactory.modifierTagAndSerializationStrategy(element)
      // Not in the real recorder: two interface checks per element, so the driver can click. It
      // replaced a class-name check (`element::class.simpleName`); the tables moved by no more than
      // run-to-run noise when it did (docs/GUEST_PROFILE.md).
      if (element is GenClickable1 || element is GenClickable2) clickable = true
      when {
        encoding == Encoding.NONE -> Unit
        serializer != null && encoding == Encoding.DIRECT_MODCACHE -> {
          val value = modifierCache.getOrPut(element) { json.encodeToDynamic(serializer, element) }
          elements.push(js("""[tag,value]"""))
        }
        serializer != null -> {
          val value = json.encodeToDynamic(serializer, element)
          elements.push(js("""[tag,value]"""))
        }
        else -> {
          elements.push(js("""[tag]"""))
        }
      }
    }
    if (clickable) clickables += id.value

    if (encoding != Encoding.NONE) {
      val id = id
      changes.push(js("""["modifier",{"id":id,"elements":elements}]"""))
    }
    if (timeCalls) stats.modifierMs += now() - start
  }

  override fun appendAdd(
    id: Id,
    tag: ChildrenTag,
    index: Int,
    child: ProtocolWidget,
  ) {
    stats.structural++
    val childId = child.id.value
    val knownId = widgets.has(childId)
    if (child.removeIndex != INVALID_INDEX) {
      check(hostSupportsRemoveDetach) { "Host v$hostVersion does not support widget re-attach" }
      check(knownId) { "Attempted to re-attach unknown widget with ID $childId" }
      removed.delete(childId)

      // Update the object associated with the remove change to indicate it's only a detach.
      changes[child.removeIndex].asDynamic()[1].detach = true
    } else {
      check(!knownId) { "Attempted to add widget with existing ID $childId" }
      widgets.set(childId, child)
    }

    val id = id
    val tag = tag
    val index = index
    changes.push(js("""["add",{"id":id,"tag":tag,"childId":childId,"index":index}]"""))
  }

  override fun appendMove(
    id: Id,
    tag: ChildrenTag,
    fromIndex: Int,
    toIndex: Int,
    count: Int,
  ) {
    stats.structural++
    val id = id
    val tag = tag
    val fromIndex = fromIndex
    val toIndex = toIndex
    val count = count
    changes.push(js("""["move",{"id":id,"tag":tag,"fromIndex":fromIndex,"toIndex":toIndex,"count":count}]"""))
  }

  override fun appendRemove(
    id: Id,
    tag: ChildrenTag,
    index: Int,
    child: ProtocolWidget,
  ) {
    stats.structural++
    removed.add(child.id.value)
    child.removeIndex = changes.length

    val id = id
    val tag = tag
    val index = index
    changes.push(js("""["remove",{"id":id,"tag":tag,"index":index,"count":1}]"""))
  }

  override fun emitChanges() {
    if (changes.length > 0) {
      removed.forEach { id ->
        val widget = widgets[id]
          ?: throw IllegalStateException("Removed widget not present in map: $id")
        widgets.delete(id)
        widget.depthFirstWalk(childrenRemover)
      }
      removed.clear()

      sendChanges(changes)
      changes.clear()
    }
  }

  private val childrenRemover: ProtocolWidget.ChildrenVisitor =
    object : ProtocolWidget.ChildrenVisitor {
      override fun visit(
        parent: ProtocolWidget,
        childrenTag: ChildrenTag,
        children: ProtocolWidgetChildren,
      ) {
        for (childWidget in children.widgets) {
          widgets.delete(childWidget.id.value)
        }
      }
    }

  class Stats {
    var creates = 0
    var properties = 0
    var modifiers = 0
    var modifierElements = 0
    var structural = 0
    var propertyMs = 0.0
    var modifierMs = 0.0

    fun reset() {
      creates = 0; properties = 0; modifiers = 0; modifierElements = 0; structural = 0
      propertyMs = 0.0; modifierMs = 0.0
    }
  }
}

/** A JsonElement as the JS value `encodeToDynamic` writes for it (numbers as numbers). */
internal fun toDynamic(e: JsonElement): dynamic = when (e) {
  is JsonNull -> null
  is JsonPrimitive -> when {
    e.isString -> e.content
    else -> e.booleanOrNull ?: e.content.toDouble()
  }
  is JsonObject -> {
    val o = js("{}")
    for ((k, v) in e) o[k] = toDynamic(v)
    o
  }
  is JsonArray -> {
    val a = js("[]")
    for (v in e) a.push(toDynamic(v))
    a
  }
}

@JsName("Array")
internal external class JsArray<E> {
  @JsName("length")
  val length: Int

  fun push(element: E)
}

internal operator fun <E> JsArray<E>.get(index: Int): E = asDynamic()[index].unsafeCast<E>()

internal fun <E> JsArray<E>.clear() {
  asDynamic().splice(0, length)
}

@JsName("Map")
internal external class JsMap<K, V> {
  val size: Int
  operator fun get(key: K): V?
  fun set(key: K, value: V)
  fun has(key: K): Boolean
  fun delete(key: K)
}

@JsName("Set")
internal external class JsSet<E> {
  val size: Int
  fun add(element: E)
  fun delete(element: E)
  fun clear()
  fun forEach(callback: (value: E) -> Unit)
}

/** Milliseconds: `performance.now()` where there is one (Node), else `Date.now()` (QuickJS: 1 ms steps). */
internal fun now(): Double = js("(typeof performance !== 'undefined') ? performance.now() : Date.now()").unsafeCast<Double>()
