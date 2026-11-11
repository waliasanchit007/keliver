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
package dev.keliver.compose.ui.semantics

import androidx.compose.runtime.Immutable
import dev.keliver.compose.wire.JsonCodec
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** The guest mirror of `androidx.compose.ui.semantics.Role`. Wire: Compose's int value. */
@Immutable
@JvmInline
@Serializable(with = RoleCodec::class)
public value class Role internal constructor(public val value: Int) {
  public companion object {
    public val Button: Role = Role(0)
    public val Checkbox: Role = Role(1)
    public val Switch: Role = Role(2)
    public val RadioButton: Role = Role(3)
    public val Tab: Role = Role(4)
    public val Image: Role = Role(5)
    public val DropdownList: Role = Role(6)
  }
}

public object RoleCodec : JsonCodec<Role>("dev.keliver.compose.Role") {
  override fun toJson(value: Role): JsonElement = JsonPrimitive(value.value)
  override fun fromJson(json: JsonElement): Role = Role(json.jsonPrimitive.int)
}
