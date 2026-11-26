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
package dev.keliver.compose.size.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.keliver.compose.coil.widget.KeliverComposeCoilWidgetFactory

/** The product flavor ships no Coil: the coil group is absent (MS4-03). */
internal fun coilFactory(): KeliverComposeCoilWidgetFactory<@Composable (Modifier) -> Unit>? = null
