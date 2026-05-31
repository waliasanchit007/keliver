/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.shared

import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.KonduitAppService
import dev.keliver.treehouse.ZiplineTreehouseUi
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
 * The [`@KonduitAppService`][KonduitAppService] annotation drives
 * the `konduit-treehouse-codegen` KSP processor — it emits
 * `GeneratedSampleAppServiceAdapter` in this same package at build
 * time, which the companion-object [Companion.Adapter] wrapper
 * below extends.
 *
 * **Why the companion-object Adapter wrapper still has to exist
 * in adopter code.** Zipline's IR plugin looks up the adapter by
 * the FQ name `<Interface>.Companion.Adapter` at code-load time.
 * KSP can generate top-level classes but cannot inject members
 * into an existing companion object — so the adopter writes the
 * 5-line wrapper, and the heavy lifting (~70 LoC of method maps
 * + outbound proxy + serializer routing) lives in the generated
 * class. See Konduit's `docs/KNOWN_BUGS.md` U12 entry for the
 * full background on Zipline #765.
 */
@KonduitAppService
public interface SampleAppService : AppService {
  public fun launch(): ZiplineTreehouseUi

  public companion object {
    internal class Adapter(
      serializers: List<KSerializer<*>>,
      serialName: String,
    ) : GeneratedSampleAppServiceAdapter(serializers, serialName)
  }
}
