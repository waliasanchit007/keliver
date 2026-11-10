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
package dev.keliver.compose.ui.graphics.drawscope

import dev.keliver.compose.ui.graphics.FilterQuality

/**
 * The guest's `DrawScope`: only its constants for now. Drawing (`Canvas`, `drawBehind`) is
 * recorded in the guest and replayed by the host in Gate B (docs/COMPOSE_GENERATOR_DESIGN.md §10).
 */
public interface DrawScope {
  public companion object {
    public val DefaultFilterQuality: FilterQuality = FilterQuality.Low
  }
}
