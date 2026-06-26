/*
 * Copyright (C) 2024 Square, Inc. Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.leaks

// spike/keliver-web: no GC hook on wasmJs — leak detection degrades to a no-op.
internal actual fun detectGc(): Gc = Gc.None
