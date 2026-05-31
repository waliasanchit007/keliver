/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress(
  "INVISIBLE_MEMBER",
  "INVISIBLE_REFERENCE",
  "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
  "EXPOSED_SUPER_CLASS",
  "EXPOSED_PARAMETER_TYPE",
  "EXPOSED_FUNCTION_RETURN_TYPE",
  "EXPOSED_PROPERTY_TYPE",
  "EXPOSED_TYPEALIAS_EXPANDED_TYPE",
)

package dev.keliver.treehouse

import app.cash.zipline.ZiplineFunction
import app.cash.zipline.internal.bridge.OutboundCallHandler
import app.cash.zipline.internal.bridge.OutboundService
import app.cash.zipline.internal.bridge.ReturningZiplineFunction
import app.cash.zipline.internal.bridge.ZiplineServiceAdapter
import kotlinx.serialization.KSerializer

/**
 * Konduit-blessed base class for hand-rolled `ZiplineServiceAdapter`s
 * on [AppService] subinterfaces. Replaces the ~95-line manual
 * adapter pattern (touching Zipline `INVISIBLE_REFERENCE` internals
 * via a file-level `@Suppress` block) that every Konduit adopter
 * has to write today because of
 * [Zipline #765](https://github.com/cashapp/zipline/issues/765).
 *
 * **Why this exists.** Zipline's IR plugin cannot auto-generate
 * adapters for interfaces that transitively extend `ZiplineService`
 * via Konduit's `AppService`. Without a manual adapter, the host
 * fails at QuickJS code-load time with
 * `Constructor 'Adapter.<init>' can not be called`. See Konduit's
 * `docs/KNOWN_BUGS.md` U12 entry for the full background.
 *
 * **Adopter usage.** Extend this class for each `AppService`
 * subinterface. The reduction from a hand-rolled adapter is
 * roughly:
 *
 * - No `@file:Suppress(...)` block. The 7-element opt-in to
 *   Zipline internals lives here, not in adopter code.
 * - No `app.cash.zipline.internal.bridge.*` imports. Adopters
 *   import [KonduitOutboundCallHandler], [KonduitOutboundService],
 *   and [konduitReturningFunction] from `dev.keliver.treehouse`.
 * - No `object : ReturningZiplineFunction<T>(...) { … }` boilerplate
 *   per method — [konduitReturningFunction] takes a single lambda.
 *
 * A typical adapter, with three methods on the service, drops
 * from ~95 LoC to ~35 LoC.
 *
 * ```kotlin
 * internal open class MyAppServiceAdapter(
 *   serializers: List<KSerializer<*>>,
 *   serialName: String = "your.pkg.MyAppService",
 * ) : KonduitAppServiceAdapter<MyAppService>(serializers, serialName) {
 *   override val simpleName = "MyAppService"
 *
 *   override fun ziplineFunctions(
 *     serializersModule: SerializersModule,
 *   ): List<ZiplineFunction<MyAppService>> {
 *     val out = mutableListOf<ZiplineFunction<MyAppService>>()
 *     out.add(konduitReturningFunction(
 *       id = "launch",
 *       signature = "fun launch(): dev.keliver.treehouse.ZiplineTreehouseUi",
 *       resultSerializer = ziplineServiceSerializer<ZiplineTreehouseUi>(),
 *       call = { it.launch() },
 *     ))
 *     // ...one konduitReturningFunction(...) per method on the interface
 *     return out
 *   }
 *
 *   override fun outboundService(
 *     callHandler: KonduitOutboundCallHandler,
 *   ): MyAppService = object : MyAppService, KonduitOutboundService {
 *     override val callHandler: KonduitOutboundCallHandler = callHandler
 *     // override every interface member with `callHandler.call(this, N)`
 *     // where N is the position in `ziplineFunctions()` above.
 *   }
 * }
 *
 * interface MyAppService : AppService {
 *   fun launch(): ZiplineTreehouseUi
 *
 *   companion object {
 *     internal class Adapter(
 *       serializers: List<KSerializer<*>>,
 *       serialName: String,
 *     ) : MyAppServiceAdapter(serializers, serialName)
 *   }
 * }
 * ```
 *
 * The function-id-to-position mapping in `outboundService(...)`
 * must still match the order of [konduitReturningFunction] calls
 * in `ziplineFunctions()`. That's the one constraint that can't
 * be hidden behind a helper — it's structural to how Zipline's
 * outbound calls work.
 */
public abstract class KonduitAppServiceAdapter<T : AppService>(
  public final override val serializers: List<KSerializer<*>>,
  public final override val serialName: String,
) : ZiplineServiceAdapter<T>()

/**
 * Konduit-blessed alias for Zipline's `OutboundCallHandler`.
 * Adopter `outboundService(...)` overrides receive a parameter of
 * this type and call `callHandler.call(this, positionalId)` for
 * each interface member.
 *
 * Aliased so adopter code doesn't import from
 * `app.cash.zipline.internal.bridge.*` directly — that import
 * triggers Kotlin's invisible-reference diagnostics, which is
 * how this whole boilerplate problem started.
 */
public typealias KonduitOutboundCallHandler = OutboundCallHandler

/**
 * Konduit-blessed alias for Zipline's `OutboundService` marker.
 * Adopter `outboundService(...)` overrides return an
 * `object : YourAppService, KonduitOutboundService { … }`.
 */
public typealias KonduitOutboundService = OutboundService

/**
 * Build one [ZiplineFunction] entry for a [KonduitAppServiceAdapter]'s
 * `ziplineFunctions(...)` list. Hides the
 * `object : ReturningZiplineFunction<T>(...) { override fun call }`
 * boilerplate that adopters would otherwise hand-write per method.
 *
 * @param id The method name. Must match the position in the
 *   `outboundService(...)` calls — `callHandler.call(this, 0)`
 *   refers to the first entry in `ziplineFunctions()`'s return
 *   list, `call(this, 1)` to the second, and so on.
 * @param signature Kotlin-style signature for Zipline diagnostics,
 *   e.g. `"fun launch(): dev.keliver.treehouse.ZiplineTreehouseUi"`.
 *   Shows up in error messages when the wire format mismatches.
 * @param argSerializers One [KSerializer] per parameter, in
 *   declaration order. Empty for parameter-less methods.
 * @param resultSerializer Serializer for the method's return type.
 *   Use `ziplineServiceSerializer<T>()` for `ZiplineService` returns
 *   (including [ZiplineTreehouseUi], [AppLifecycle], etc.), or
 *   `serializersModule.serializer<T>()` for `@Serializable` value
 *   types.
 * @param call A lambda that invokes the method on a service instance.
 *   For `Unit`-returning methods, write `{ it.close(); Unit }` so
 *   the lambda still returns the right type.
 */
public fun <T : AppService> konduitReturningFunction(
  id: String,
  signature: String,
  argSerializers: List<KSerializer<*>> = emptyList(),
  resultSerializer: KSerializer<*>,
  call: (T) -> Any?,
): ZiplineFunction<T> = object : ReturningZiplineFunction<T>(
  id = id,
  signature = signature,
  argSerializers = argSerializers,
  resultSerializer = resultSerializer,
) {
  override fun call(service: T, args: List<*>): Any? = call(service)
}
