/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.sample.shared

import dev.konduit.treehouse.AppService
import dev.konduit.treehouse.ZiplineTreehouseUi

/**
 * Top-level `AppService` for the sample. The guest binds an
 * implementation under the name `"app"` (see `guest/Main.kt`); each
 * host's `TreehouseApp.Spec.create()` takes that bound service via
 * `zipline.take<SampleAppService>("app")`.
 *
 * `launch()` is what mounts the Compose root. Calling it returns a
 * `ZiplineTreehouseUi` — Konduit's host adapter wraps that into a
 * `TreehouseContent` mount point inside the host's Compose tree.
 *
 * `AppService` extends `ZiplineService`, which is why this interface
 * sits in commonMain rather than only on the guest side — every
 * platform involved in the RPC needs the symbol on its classpath.
 */
public interface SampleAppService : AppService {
  public fun launch(): ZiplineTreehouseUi
}
