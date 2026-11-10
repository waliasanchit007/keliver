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
package dev.keliver.compose.material.icons

/**
 * The guest mirror of `androidx.compose.material.icons.Icons`. Each icon is an extension property
 * in its theme's package (`dev.keliver.compose.material.icons.filled.ChevronRight`), generated
 * from the icon group's allowlist.
 */
public object Icons {
  public object Filled
  public object Outlined
  public object Rounded
  public object AutoMirrored {
    public object Filled
    public object Outlined
  }
  public val Default: Filled = Filled
}
