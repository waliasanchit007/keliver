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
package dev.keliver.spike.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.keliver.compose.ui.graphics.ColorCodec
import dev.keliver.compose.ui.unit.DpCodec
import dev.keliver.compose.ui.unit.TextUnitCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The host half of the spike's runtime (docs/COMPOSE_GENERIC_MECHANISMS.md), written once.

// ---- The hand codecs that stay: arithmetic value classes (literal colours only in the spike;
// a theme reference resolves as in gatea's Converters.kt). ----

public fun decodeColor(j: JsonElement): Color {
  val k = ColorCodec.fromJson(j)
  return if (k == dev.keliver.compose.ui.graphics.Color.Unspecified) Color.Unspecified else Color(k.argb)
}

public fun decodeDp(j: JsonElement): Dp = Dp(DpCodec.fromJson(j).value)

public fun decodeTextUnit(j: JsonElement): TextUnit {
  val k = TextUnitCodec.fromJson(j)
  return when {
    k.isSp -> k.value.sp
    k.isEm -> k.value.em
    else -> TextUnit.Unspecified
  }
}

public fun decodeOffset(j: JsonElement): Offset = j.jsonArray.let { Offset(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float) }

public fun decodeSize(j: JsonElement): Size = j.jsonArray.let { Size(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float) }

// ---- Remote objects (mechanism 2). ----

/** What the generator writes per state class: field reads and member calls by name. */
public interface RemoteDispatcher<S> {
  public fun read(state: S, name: String): JsonElement
  public suspend fun invoke(state: S, member: String, a: JsonObject): JsonElement
}

/**
 * Runs a guest's commands on the host's real [state] and reports the fields the guest observes
 * ([wire]: `RemoteObject.wire`). Each command runs once, by its sequence number, and is
 * acknowledged when the real member returns; observed fields are reported from a `snapshotFlow`,
 * so on change and at most once a frame.
 */
@Composable
public fun <S> RemoteHost(wire: JsonObject, state: S, dispatcher: RemoteDispatcher<S>, report: (JsonObject) -> Unit) {
  val started = remember { mutableSetOf<Int>() }
  for (c in wire["cmd"]?.jsonArray.orEmpty()) {
    val cmd = c.jsonArray
    val seq = cmd[0].jsonPrimitive.int
    key(seq) {
      LaunchedEffect(seq) {
        if (!started.add(seq)) return@LaunchedEffect
        dispatcher.invoke(state, cmd[1].jsonPrimitive.content, cmd[2].jsonObject)
        report(buildJsonObject { put("done", JsonArray(listOf(JsonPrimitive(seq)))) })
      }
    }
  }
  val observed = wire["obs"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
  LaunchedEffect(observed) {
    snapshotFlow { observed.associateWith { dispatcher.read(state, it) } }
      .collect { report(buildJsonObject { put("f", JsonObject(it)) }) }
  }
}
