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
package dev.keliver.host

/**
 * Marker identifying this artifact as the `keliver-host` facade. The
 * module exists primarily to aggregate the public host-side Keliver
 * API surface via `api` dependencies; this constant gives the KMP
 * compiler something to package so each platform produces a klib /
 * jar artifact suitable for publishing.
 */
@Suppress("unused")
internal const val FACADE_MARKER: String = "keliver-host"
