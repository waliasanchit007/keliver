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
package dev.keliver.compose.internal

import dev.keliver.Modifier

/**
 * A lambda modifier (`Modifier.clickable { … }`) before it reaches the protocol: the lambda stays
 * in the guest, and [factory] builds the wire element from the handle the widget assigns
 * (docs/COMPOSE_GENERATOR_DESIGN.md §8). Never sent: [splitModifierEvents] replaces it.
 */
public class ModifierEventElement(
  public val lambda: () -> Unit,
  public val factory: (handle: Int) -> Modifier.Element,
) : Modifier.UnscopedElement {
  // Equal to any other event element: what goes on the wire is the factory's element, and the
  // lambda may change on every recomposition without resending the chain.
  override fun equals(other: Any?): Boolean = other is ModifierEventElement
  override fun hashCode(): Int = 1
}

/** A widget's chain without lambdas, and the event sink that calls them by handle. */
public class SplitModifier(public val modifier: Modifier, public val events: ((Int) -> Unit)?)

public fun splitModifierEvents(modifier: Modifier): SplitModifier {
  var hasEvents = false
  modifier.forEach { if (it is ModifierEventElement) hasEvents = true }
  if (!hasEvents) return SplitModifier(modifier, null)
  val lambdas = ArrayList<() -> Unit>()
  var out: Modifier = Modifier
  modifier.forEach { e ->
    out = out then if (e is ModifierEventElement) {
      lambdas += e.lambda
      e.factory(lambdas.size - 1)
    } else {
      e
    }
  }
  return SplitModifier(out) { handle -> lambdas.getOrNull(handle)?.invoke() }
}

/** Compose's `model: Any?` on the wire: a URL. Other models are Gate B. */
public fun imageModel(model: Any?): String? = when (model) {
  null -> null
  is String -> model
  else -> model.toString()
}
