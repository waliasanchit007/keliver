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
package dev.keliver.compose.wire

/**
 * Members of a Compose mirror that Compose itself does not have: the generated API and its
 * hand-written runtime use them to talk to the host. Screens don't, so their code stays Compose's.
 */
@RequiresOptIn(
  message = "Keliver's generated API uses this to talk to the host; it is not Compose API, so a screen should not.",
  level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
public annotation class KeliverGeneratedApi
