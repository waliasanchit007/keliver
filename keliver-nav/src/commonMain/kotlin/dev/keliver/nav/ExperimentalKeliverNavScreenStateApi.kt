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
package dev.keliver.nav

/**
 * The generated `@ScreenState` registry ([ScreenStateRegistry], [ScreenStateEntry] and the errors
 * opening a state raises, [ScreenStateException]). Its callers are still to come: the device route that opens a named
 * state from a link (docs/PLAN_2026-11.md ED3) and the browser's state links (CAP6). Members may
 * be added, changed or removed in any release until they exist.
 *
 * [ScreenState] and [NamedState] themselves are not experimental.
 */
@RequiresOptIn("Keliver's generated @ScreenState registry is experimental and may change in any release.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalKeliverNavScreenStateApi
