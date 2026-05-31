/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.treehouse

/**
 * Marks an [AppService] subinterface as the source for a generated
 * [KeliverAppServiceAdapter] subclass. The `keliver-treehouse-codegen`
 * KSP processor scans the build for `@KeliverAppService`-annotated
 * interfaces and emits a `Generated<Name>Adapter` open class
 * implementing the full [Zipline #765](https://github.com/cashapp/zipline/issues/765)
 * workaround.
 *
 * Adopter usage drops the ~70-line `ManualXxxAppServiceAdapter.kt`
 * to a tiny companion-object stub on the interface itself:
 *
 * ```kotlin
 * @KeliverAppService
 * interface MyAppService : AppService {
 *   fun launch(): ZiplineTreehouseUi
 *
 *   companion object {
 *     // Zipline IR looks up `Companion.Adapter` by name when wiring
 *     // service take/bind calls — keep this exact class name + ctor
 *     // signature, the generator output requires it.
 *     internal class Adapter(
 *       serializers: List<KSerializer<*>>,
 *       serialName: String,
 *     ) : GeneratedMyAppServiceAdapter(serializers, serialName)
 *   }
 * }
 * ```
 *
 * The generated class lives in the same package as the annotated
 * interface, suffixed `Generated<InterfaceName>Adapter`. Adding new
 * methods to the interface re-runs the processor automatically — no
 * manual reconciliation between the function list + outbound impl
 * (that's the gotcha that bit early adopters of the hand-rolled
 * pattern).
 *
 * **Wire-format constraint.** Method-name-to-position mapping is
 * stable across host + guest as long as both sides see the same
 * source. The generator orders methods by Kotlin declaration order;
 * adding a method in the middle of an interface still re-orders
 * positional call IDs and will break any guest bundle that was
 * compiled against the old order. For breaking schema changes,
 * bump `widgetVersion` in your guest's `StandardAppLifecycle` so
 * older hosts fail fast at `codeLoadFailed` instead of mis-decoding.
 *
 * Retention is **SOURCE** — the annotation is consumed at build
 * time by the KSP processor, never reaches the runtime classpath.
 * No effect on bundle size.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
public annotation class KeliverAppService
