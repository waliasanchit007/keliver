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
 * Back-handling API that serves keliver-nav's retained back stack ([LocalBackHandlersEnabled]) and
 * NF5's hidden content (`TreehouseContent(backEnabled)`, keliver-treehouse-host-composeui). Its
 * shape follows their needs, which are still open in docs/PLAN_2026-11.md (NF1, NF5), so it may be
 * changed or removed in any release.
 */
@RequiresOptIn("Keliver's back-handler gate is experimental and may change in any release.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalKeliverBackApi
