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
package dev.keliver.nav

/**
 * The retained back stack ([KeliverNavRetention], [KeliverNavFrame], [KeliverRetainedStack],
 * [LaunchedWhileVisible], [keliverNavEntryLifecycle]). Its shape depends on decisions still open in
 * docs/PLAN_2026-11.md (NF1): a schema-level container that hides an entry, whether retention
 * becomes the default, and its depth; members may be added, changed or removed in any release.
 *
 * Results passed back ([KeliverNavResultKey], [KeliverNavController.popWithResult],
 * [LaunchedOnResult]) carry it too: they are not saved today, and restoring the stack after the
 * guest is recreated would need serializable values, which would change the key's shape.
 */
@RequiresOptIn("Keliver's retained back stack is experimental and may change in any release.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ExperimentalKeliverNavRetentionApi
