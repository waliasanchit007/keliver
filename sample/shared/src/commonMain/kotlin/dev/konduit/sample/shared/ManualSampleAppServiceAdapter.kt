/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 *
 * Manual `ZiplineServiceAdapter` for [SampleAppService]. Required
 * because Zipline's IR plugin cannot auto-generate adapters for
 * interfaces that transitively extend `ZiplineService` through
 * `AppService` — see https://github.com/cashapp/zipline/issues/765
 * and Konduit's `docs/KNOWN_BUGS.md` U12 entry.
 *
 * Uses Konduit's [KonduitAppServiceAdapter] base class +
 * [konduitReturningFunction] helper so the imports come from
 * `dev.konduit.treehouse` (Konduit-blessed) instead of
 * `app.cash.zipline.internal.bridge.*` (Zipline internals).
 *
 * Adopter cost reduces from ~95 LoC + 7-entry @file:Suppress to
 * ~70 LoC + 2-entry @file:Suppress. Most of the per-method
 * boilerplate disappears into the helper functions. The
 * `INVISIBLE_*` suppressions stay because Kotlin checks visibility
 * at every use site — typealiases don't change that. Until Zipline
 * #765 ships, this is the smallest possible surface.
 *
 * When you add a new method to `SampleAppService`: append a matching
 * [konduitReturningFunction] block in `ziplineFunctions(...)` AND
 * a delegating override in `outboundService(...)`'s anonymous
 * object. The function-id-to-position mapping must match between
 * the two.
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.konduit.sample.shared

import app.cash.zipline.ZiplineFunction
import app.cash.zipline.ziplineServiceSerializer
import dev.konduit.treehouse.AppLifecycle
import dev.konduit.treehouse.KonduitAppServiceAdapter
import dev.konduit.treehouse.KonduitOutboundCallHandler
import dev.konduit.treehouse.KonduitOutboundService
import dev.konduit.treehouse.ZiplineTreehouseUi
import dev.konduit.treehouse.konduitReturningFunction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

internal open class ManualSampleAppServiceAdapter(
  serializers: List<KSerializer<*>>,
  serialName: String = "dev.konduit.sample.shared.SampleAppService",
) : KonduitAppServiceAdapter<SampleAppService>(serializers, serialName) {

  override val simpleName: String = "SampleAppService"

  override fun ziplineFunctions(
    serializersModule: SerializersModule,
  ): List<ZiplineFunction<SampleAppService>> {
    val out = mutableListOf<ZiplineFunction<SampleAppService>>()
    out.add(
      konduitReturningFunction<SampleAppService>(
        id = "launch",
        signature = "fun launch(): dev.konduit.treehouse.ZiplineTreehouseUi",
        resultSerializer = ziplineServiceSerializer<ZiplineTreehouseUi>(),
        call = { it.launch() },
      ),
    )
    out.add(
      konduitReturningFunction<SampleAppService>(
        id = "appLifecycle",
        signature = "fun appLifecycle(): dev.konduit.treehouse.AppLifecycle",
        resultSerializer = ziplineServiceSerializer<AppLifecycle>(),
        call = { it.appLifecycle },
      ),
    )
    out.add(
      konduitReturningFunction<SampleAppService>(
        id = "close",
        signature = "fun close(): kotlin.Unit",
        resultSerializer = serializersModule.serializer<Unit>(),
        call = { it.close(); Unit },
      ),
    )
    return out
  }

  override fun outboundService(
    callHandler: KonduitOutboundCallHandler,
  ): SampleAppService = object : SampleAppService, KonduitOutboundService {
    override val callHandler: KonduitOutboundCallHandler = callHandler

    // call IDs must match the position of each function in
    // `ziplineFunctions()` above. Re-order at your peril.
    override fun launch(): ZiplineTreehouseUi =
      callHandler.call(this, 0) as ZiplineTreehouseUi

    override val appLifecycle: AppLifecycle
      get() = callHandler.call(this, 1) as AppLifecycle

    override fun close() {
      callHandler.call(this, 2)
    }
  }
}
