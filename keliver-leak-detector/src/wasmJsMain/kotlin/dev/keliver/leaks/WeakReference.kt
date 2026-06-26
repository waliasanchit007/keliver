/*
 * Copyright (C) 2024 Square, Inc. Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.leaks

// spike/keliver-web: no WeakRef on wasmJs — hold a strong ref (leak checks no-op).
internal actual fun hasWeakReference(): Boolean = false

internal actual class WeakReference<T : Any> actual constructor(referred: T) {
  private val ref = referred
  actual fun get(): T? = ref
}
