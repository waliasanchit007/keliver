/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.sample.shared

import dev.konduit.treehouse.AppService
import dev.konduit.treehouse.ZiplineTreehouseUi
import kotlinx.serialization.KSerializer

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
 *
 * The companion `Adapter` is a manual workaround for Zipline issue
 * [#765](https://github.com/cashapp/zipline/issues/765) — the IR
 * plugin cannot auto-generate adapters for interfaces that
 * transitively extend `ZiplineService` via `AppService`. Without
 * this companion, the host's `EventListener.codeLoadFailed` fires
 * at QuickJS load time with "Constructor 'Adapter.<init>' can not
 * be called". See [ManualSampleAppServiceAdapter] for the body.
 */
public interface SampleAppService : AppService {
  public fun launch(): ZiplineTreehouseUi

  public companion object {
    internal class Adapter(
      serializers: List<KSerializer<*>>,
      serialName: String,
    ) : ManualSampleAppServiceAdapter(serializers, serialName)
  }
}
