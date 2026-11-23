/*
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
import dev.keliver.portal.WidgetNode
import dev.keliver.portal.isValidUnder
import dev.keliver.portal.modifierSpecs

// K1: scoped layout modifiers (flex, margin, width, …) are members of
// RowScope/ColumnScope/BoxScope — they exist only inside those widgets'
// content lambdas. The modifier panel offers them only where they fit.

/** The modifier palette for a node whose scope-giving parent is [parentType]. */
internal fun modifiersOfferedUnder(parentType: String?): List<String> =
  modifierSpecs.filter { it.isValidUnder(parentType) }.map { it.name }

/**
 * The widget whose content lambda node [id] sits in. Condition/Else/Repeat are
 * `if`/`else`/`forEach` in source and add no receiver, so look through them. Null at
 * the root (and for an unknown id).
 */
internal fun scopeParentType(root: WidgetNode, id: Int): String? {
  val chain = pathTo(root, id) ?: return null // root .. node
  return chain.dropLast(1).lastOrNull { it.type !in dev.keliver.portal.LOGIC_NODE_TYPES }?.type
}

private fun pathTo(n: WidgetNode, id: Int): List<WidgetNode>? {
  if (n.id == id) return listOf(n)
  for (c in n.children) pathTo(c, id)?.let { return listOf(n) + it }
  return null
}
