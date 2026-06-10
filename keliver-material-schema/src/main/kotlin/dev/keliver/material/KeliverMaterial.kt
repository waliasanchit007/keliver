/*
 * Copyright (C) 2025 Square, Inc.
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
package dev.keliver.material

import dev.keliver.layout.RedwoodLayout
import dev.keliver.lazylayout.RedwoodLazyLayout
import dev.keliver.schema.Modifier
import dev.keliver.schema.Property
import dev.keliver.schema.Schema
import dev.keliver.schema.Schema.Dependency
import dev.keliver.schema.Widget
import dev.keliver.material.api.TextFieldState

@Schema(
  members = [
    TextInput::class,
    Text::class,
    Image::class,
    Button::class,
    Reuse::class,
  ],
  dependencies = [
    Dependency(1, RedwoodLayout::class),
    Dependency(2, RedwoodLazyLayout::class),
  ],
)
public interface KeliverMaterial

@Widget(1)
public data class TextInput(
  @Property(1)
  val state: TextFieldState = TextFieldState(),
  @Property(2)
  val hint: String = "",
  @Property(3)
  val onChange: ((TextFieldState) -> Unit)? = null,
)

@Widget(2)
public data class Text(
  @Property(1) val text: String,
)

@Widget(3)
public data class Image(
  @Property(1) val url: String,
  @Property(2) val onClick: (() -> Unit)? = null,
)

@Widget(4)
public data class Button(
  @Property(1) val text: String?,
  @Property(2) val enabled: Boolean = true,
  @Property(3) val onClick: (() -> Unit)? = null,
)

@Modifier(-4_543_827) // -4_543_827 is a reserved tag.
public object Reuse
