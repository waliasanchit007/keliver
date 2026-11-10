/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.compose.wire

import dev.keliver.compose.foundation.shape.RoundedCornerShape
import dev.keliver.compose.ui.Alignment
import dev.keliver.compose.ui.graphics.Color
import dev.keliver.compose.ui.graphics.Shape
import dev.keliver.compose.ui.unit.Dp
import dev.keliver.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToDynamic

/**
 * The guest's fast protocol path encodes property values with encodeToDynamic; each codec must
 * give there what it gives as JSON text (an array value was once flattened to its first element).
 */
class DynamicCodecTest {
  private fun <T> same(serializer: KSerializer<T>, value: T) =
    assertEquals(Json.encodeToString(serializer, value), JSON.stringify(Json.encodeToDynamic(serializer, value)).replace(".0", ""))

  @Test fun dynamicEqualsText() {
    same(Alignment.serializer(), Alignment.TopStart.let { Alignment.BottomEnd })
    same(Dp.serializer(), 16.dp)
    same(Color.serializer(), Color(0xFF1F1F1F))
    same(Shape.serializer(), RoundedCornerShape(12.dp))
  }
}
