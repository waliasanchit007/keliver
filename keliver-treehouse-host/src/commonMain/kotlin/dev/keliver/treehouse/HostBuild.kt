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
package dev.keliver.treehouse

/**
 * The line a [TreehouseApp.Factory] logs once, when it is created, at debug level
 * ([TreehousePlatform.logDebug]): which keliver-treehouse-host build the host runs, by its version,
 * for example `keliver-treehouse-host 0.5.0`. It answers "which Keliver is this app on?" from a
 * device log.
 *
 * [KELIVER_HOST_VERSION] is generated from the build's version (the `generateKeliverHostBuild`
 * task). The reference app's local-maven route (`reference/inventory/ci/local-maven`) publishes this
 * checkout's libraries under a version of its own and finds that version here in the device's log,
 * which shows the host was built against them and not against Maven Central.
 */
internal const val HOST_BUILD_LINE: String = "keliver-treehouse-host $KELIVER_HOST_VERSION"
