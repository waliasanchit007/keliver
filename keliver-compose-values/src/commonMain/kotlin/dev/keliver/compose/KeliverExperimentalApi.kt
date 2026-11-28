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
package dev.keliver.compose

/**
 * Marks API that Keliver adds to Compose's (the extras: `Shimmer`, `pressSignal`). Compose mirrors
 * are stable in 0.5.0; Keliver-only API is experimental until the 0.5.0 freeze (docs/MS4_PLAN.md, Q10),
 * so a screen that uses it opts in with `@OptIn(KeliverExperimentalApi::class)`.
 */
@RequiresOptIn(
  message = "This API is Keliver's own, not Compose's, and is experimental: it may change before it is stable.",
  level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class KeliverExperimentalApi
