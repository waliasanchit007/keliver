/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 *
 * Manual `ZiplineServiceAdapter` for [SampleAppService]. Required
 * because Zipline's IR plugin cannot auto-generate adapters for
 * interfaces that transitively extend `ZiplineService` through
 * `AppService` — see https://github.com/cashapp/zipline/issues/765.
 *
 * Symptom of NOT having this file (or the matching companion-object
 * `Adapter` subclass on `SampleAppService`): the host's
 * `EventListener.codeLoadFailed` fires at QuickJS load with
 *
 *   QuickJsException: Constructor 'Adapter.<init>' can not be called:
 *   No constructor found for symbol '…SampleAppService.Companion.Adapter…
 *     <init>(kotlin.collections.List<kotlinx.serialization.KSerializer<*>>;
 *     kotlin.String)'
 *
 * If you add new methods to `SampleAppService`, add a matching
 * `ReturningZiplineFunction` block in `ziplineFunctions(...)` AND a
 * delegating override in `outboundService(...)`'s anonymous object,
 * keeping the function ids stable across host + guest.
 */
@file:Suppress(
  "INVISIBLE_MEMBER",
  "INVISIBLE_REFERENCE",
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
  "EXPOSED_PARAMETER_TYPE",
  "EXPOSED_SUPER_CLASS",
  "EXPOSED_FUNCTION_RETURN_TYPE",
  "EXPOSED_PROPERTY_TYPE",
)

package dev.konduit.sample.shared

import app.cash.zipline.ZiplineFunction
import app.cash.zipline.internal.bridge.OutboundCallHandler
import app.cash.zipline.internal.bridge.OutboundService
import app.cash.zipline.internal.bridge.ReturningZiplineFunction
import app.cash.zipline.internal.bridge.ZiplineServiceAdapter
import app.cash.zipline.ziplineServiceSerializer
import dev.konduit.treehouse.AppLifecycle
import dev.konduit.treehouse.ZiplineTreehouseUi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

internal open class ManualSampleAppServiceAdapter(
  override val serializers: List<KSerializer<*>>,
  override val serialName: String = "dev.konduit.sample.shared.SampleAppService",
) : ZiplineServiceAdapter<SampleAppService>() {

  override val simpleName: String = "SampleAppService"

  override fun ziplineFunctions(
    serializersModule: SerializersModule,
  ): List<ZiplineFunction<SampleAppService>> {
    val launchFunction = object : ReturningZiplineFunction<SampleAppService>(
      id = "launch",
      signature = "fun launch(): dev.konduit.treehouse.ZiplineTreehouseUi",
      argSerializers = emptyList(),
      resultSerializer = ziplineServiceSerializer<ZiplineTreehouseUi>(),
    ) {
      override fun call(service: SampleAppService, args: List<*>): Any? = service.launch()
    }
    val appLifecycleFunction = object : ReturningZiplineFunction<SampleAppService>(
      id = "appLifecycle",
      signature = "fun appLifecycle(): dev.konduit.treehouse.AppLifecycle",
      argSerializers = emptyList(),
      resultSerializer = ziplineServiceSerializer<AppLifecycle>(),
    ) {
      override fun call(service: SampleAppService, args: List<*>): Any? = service.appLifecycle
    }
    val closeFunction = object : ReturningZiplineFunction<SampleAppService>(
      id = "close",
      signature = "fun close(): kotlin.Unit",
      argSerializers = emptyList(),
      resultSerializer = serializersModule.serializer<Unit>(),
    ) {
      override fun call(service: SampleAppService, args: List<*>): Any? {
        service.close()
        return Unit
      }
    }
    // Use a mutable list + .add(...) instead of listOf(...) — the
    // vararg `listOf` infers the element type from `ReturningZiplineFunction`,
    // an `INVISIBLE_REFERENCE` Zipline internal, which Kotlin rejects
    // at the call site even with the file-level Suppress.
    val out = mutableListOf<ZiplineFunction<SampleAppService>>()
    out.add(launchFunction)
    out.add(appLifecycleFunction)
    out.add(closeFunction)
    return out
  }

  override fun outboundService(callHandler: OutboundCallHandler): SampleAppService {
    return object : SampleAppService, OutboundService {
      override val callHandler: OutboundCallHandler = callHandler

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
}
