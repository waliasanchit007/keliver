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

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The optional features the host this composition is shown on says it has, by name (for example
 * keliver-nav's retained container, `"keliver.nav.retainedContainer@1"`). The guest reads it at run
 * time to use a feature only on a host that has it, so one bundle runs on older and newer hosts (D2).
 *
 * A Treehouse guest gets the set its host's `TreehouseApp.Spec.hostFeatures` declares. A host built
 * before hosts could declare features, and any composition not bound to a host, has the empty set:
 * "nothing optional", never "everything".
 */
@ExperimentalKeliverHostFeaturesApi
public val LocalHostFeatures: ProvidableCompositionLocal<Set<String>> = staticCompositionLocalOf { emptySet() }

/**
 * Host features ([LocalHostFeatures]) are new in 0.5.0 and serve keliver-nav's retained back stack;
 * their shape may change in any release.
 */
@RequiresOptIn("Keliver's host features are experimental and may change in any release.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalKeliverHostFeaturesApi
