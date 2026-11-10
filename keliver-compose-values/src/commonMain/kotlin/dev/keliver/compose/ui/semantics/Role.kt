/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
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
