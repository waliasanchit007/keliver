/*
 * Copyright (C) 2024 Square, Inc.
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
@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.keliver.leaks

// The JS WeakRef the js target uses. A Kotlin/Wasm object crosses into JS as itself (a Wasm GC
// reference, not a copy), so the WeakRef observes the very object the caller passed.
internal actual fun hasWeakReference(): Boolean = jsHasWeakRef()

internal actual class WeakReference<T : Any> actual constructor(referred: T) {
  private val weakRef: JsAny = jsNewWeakRef(referred.toJsReference())

  actual fun get(): T? {
    val referent = jsDeref(weakRef) ?: return null
    @Suppress("UNCHECKED_CAST")
    return (referent as JsReference<T>).get()
  }
}

private fun jsHasWeakRef(): Boolean = js("typeof WeakRef === 'function'")

private fun jsNewWeakRef(referent: JsAny): JsAny = js("new WeakRef(referent)")

// deref() answers undefined once the referent is collected; `?? null` keeps that a Kotlin null.
private fun jsDeref(weakRef: JsAny): JsAny? = js("weakRef.deref() ?? null")
