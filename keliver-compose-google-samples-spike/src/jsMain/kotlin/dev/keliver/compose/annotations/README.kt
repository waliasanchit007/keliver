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
package dev.keliver.compose.annotations

/**
 * The files beside this one are the spike's stand-ins for annotations the generated guest API does
 * not have yet: `@Preview` and Compose's opt-in markers (Gate B part b planned to copy the markers;
 * MS4-07 generates every public name of an included package). They carry no behaviour, so a
 * sample blocked only by them would compile once MS4-07 lands. analyze.py counts those samples.
 */
internal object AnnotationStandIns
