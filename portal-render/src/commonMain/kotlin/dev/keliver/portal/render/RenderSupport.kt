package dev.keliver.portal.render

import dev.keliver.layout.api.Constraint
import dev.keliver.layout.api.CrossAxisAlignment
import dev.keliver.layout.api.MainAxisAlignment
import dev.keliver.layout.api.Overflow

// Bridges between the tree's Int encodings and the layout value classes.
// Ordinals match keliver-layout-api/properties.kt exactly.

fun constraintOf(v: Int): Constraint = if (v == 1) Constraint.Fill else Constraint.Wrap

fun crossAxisOf(v: Int): CrossAxisAlignment = when (v) {
  1 -> CrossAxisAlignment.Center
  2 -> CrossAxisAlignment.End
  3 -> CrossAxisAlignment.Stretch
  else -> CrossAxisAlignment.Start
}

fun mainAxisOf(v: Int): MainAxisAlignment = when (v) {
  1 -> MainAxisAlignment.Center
  2 -> MainAxisAlignment.End
  3 -> MainAxisAlignment.SpaceBetween
  4 -> MainAxisAlignment.SpaceAround
  5 -> MainAxisAlignment.SpaceEvenly
  else -> MainAxisAlignment.Start
}

fun overflowOf(v: Int): Overflow = if (v == 1) Overflow.Scroll else Overflow.Clip
