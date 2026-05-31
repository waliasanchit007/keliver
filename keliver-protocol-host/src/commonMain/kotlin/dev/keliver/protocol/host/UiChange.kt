/*
 * Copyright (C) 2024 Square, Inc.
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
package dev.keliver.protocol.host

import dev.keliver.Modifier
import dev.keliver.RedwoodCodegenApi
import dev.keliver.protocol.Change
import dev.keliver.protocol.ChangesSink
import dev.keliver.protocol.ChildrenChange
import dev.keliver.protocol.Create
import dev.keliver.protocol.Id
import dev.keliver.protocol.ModifierChange
import dev.keliver.protocol.PropertyChange
import dev.keliver.protocol.PropertyTag
import dev.keliver.protocol.WidgetTag

/**
 * A version of [Change] whose contents have already been deserialized from JSON and is thus
 * cheap to apply on the UI thread.
 */
public sealed interface UiChange {
  public val id: Id

  public companion object {
    private const val REUSE_MODIFIER_TAG = -4_543_827

    /**
     * Deserialize a [Change] into a [UiChange]
     *
     * @return `null` if there was a protocol mismatch and the supplied [protocol]'s mismatch
     * handler returned `null`.
     */
    @OptIn(RedwoodCodegenApi::class)
    public fun fromProtocol(
      protocol: HostProtocol,
      change: Change,
    ): UiChange? {
      val factory = when (protocol) {
        is GeneratedHostProtocol -> protocol
      }

      return when (change) {
        is Create -> UiCreate(change.id, change.tag)
        is ChildrenChange -> UiChildrenChange(change)
        is PropertyChange -> {
          val widgetProtocol = factory.widget(change.widgetTag)
            ?: return null
          val propertyDeserializer = widgetProtocol.propertyDeserializer(change.propertyTag)
            ?: return null
          val value = protocol.json.decodeFromJsonElement(propertyDeserializer, change.value)
          UiPropertyChange(change.id, change.propertyTag, value)
        }
        is ModifierChange -> {
          var reuse = false
          val modifier = change.elements.fold<_, Modifier>(Modifier) { outer, element ->
            if (element.tag.value == REUSE_MODIFIER_TAG) {
              reuse = true
            }
            outer.then(factory.createModifier(element))
          }
          UiModifierChange(change.id, reuse, modifier)
        }
      }
    }
  }
}

/** A version of [ChangesSink] which consumes [UiChange]s. */
public fun interface UiChangesSink {
  public fun sendChanges(changes: List<UiChange>)
}

/** @suppress */
@RedwoodCodegenApi
public class UiCreate(
  override val id: Id,
  public val tag: WidgetTag,
) : UiChange

/** @suppress */
@RedwoodCodegenApi
public class UiPropertyChange(
  override val id: Id,
  public val tag: PropertyTag,
  public val value: Any?,
) : UiChange

/** @suppress */
@RedwoodCodegenApi
public class UiModifierChange(
  override val id: Id,
  public val reuse: Boolean,
  public val modifier: Modifier,
) : UiChange

/** @suppress */
@RedwoodCodegenApi
public class UiChildrenChange(
  public val change: ChildrenChange,
) : UiChange {
  override val id: Id get() = change.id
}
