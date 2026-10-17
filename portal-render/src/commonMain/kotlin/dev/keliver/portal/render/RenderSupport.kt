package dev.keliver.portal.render

import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment
import dev.keliver.layout.api.Overflow
import dev.keliver.portal.WidgetNode
import dev.keliver.ui.Dp
import dev.keliver.ui.Margin

// Bridges between the tree's Int encodings and the layout value classes.
// Ordinals match keliver-layout-api/properties.kt exactly.

public fun constraintOf(v: Int): Constraint = if (v == 1) Constraint.Fill else Constraint.Wrap

public fun crossAxisOf(v: Int): CrossAxisAlignment = when (v) {
  1 -> CrossAxisAlignment.Center
  2 -> CrossAxisAlignment.End
  3 -> CrossAxisAlignment.Stretch
  else -> CrossAxisAlignment.Start
}

public fun mainAxisOf(v: Int): MainAxisAlignment = when (v) {
  1 -> MainAxisAlignment.Center
  2 -> MainAxisAlignment.End
  3 -> MainAxisAlignment.SpaceBetween
  4 -> MainAxisAlignment.SpaceAround
  5 -> MainAxisAlignment.SpaceEvenly
  else -> MainAxisAlignment.Start
}

public fun overflowOf(v: Int): Overflow = if (v == 1) Overflow.Scroll else Overflow.Clip

/**
 * K1: a Margin value rides as four Double sides "<prefix>.start/.end/.top/.bottom"
 * (absent = 0, Dp is non-negative so a negative side clamps to 0).
 */
internal fun marginOf(node: WidgetNode, prefix: String): Margin = Margin(
  start = Dp(node.dblB("$prefix.start").coerceAtLeast(0.0)),
  end = Dp(node.dblB("$prefix.end").coerceAtLeast(0.0)),
  top = Dp(node.dblB("$prefix.top").coerceAtLeast(0.0)),
  bottom = Dp(node.dblB("$prefix.bottom").coerceAtLeast(0.0)),
)
