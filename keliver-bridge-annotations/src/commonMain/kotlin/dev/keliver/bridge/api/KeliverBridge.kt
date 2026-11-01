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
package dev.keliver.bridge.api

/**
 * Marks the interface of an app's actions (open a route, share, track, a picker's result) as
 * a bridge between its guest and its hosts. The `keliver-bridge-codegen` KSP processor
 * generates, next to it, the `ZiplineService`, an object to bind and take it, and the iOS
 * callbacks (`docs/BRIDGE_CODEGEN_DESIGN.md`).
 *
 * ```
 * @KeliverBridge(name = "ShopActions", version = 2)
 * interface ShopActions {
 *   suspend fun openRoute(route: String): Boolean
 *   @BridgeTimeout(seconds = 0)
 *   suspend fun pickAddress(request: AddressRequest): AddressResult
 *   fun track(event: String, properties: Map<String, String>)
 * }
 * ```
 *
 * @property name the name the bridge is bound under; empty means the interface's simple name.
 * @property version the contract's version, for the compatibility gate (B4).
 * @property timeoutSeconds how long the host waits for the app's answer to a call, unless a
 *   function's [BridgeTimeout] says otherwise; 0 means no limit.
 *
 * The defaults are repeated in keliver-bridge-codegen, which cannot always read them: change both.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class KeliverBridge(
  val name: String = "",
  val version: Int = 1,
  val timeoutSeconds: Int = 30,
)

/**
 * The limit for one function of a [KeliverBridge], in seconds; 0 means none, for a call that
 * waits on the user (a picker, a web page's result). The guest's own cancel still ends it.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class BridgeTimeout(val seconds: Int)
