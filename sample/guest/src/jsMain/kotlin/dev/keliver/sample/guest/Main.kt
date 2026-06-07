/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.guest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.cash.zipline.Zipline
import dev.keliver.http.HostHttpProvider
import dev.keliver.http.KeliverHttp
import dev.keliver.sample.schema.compose.Column
import dev.keliver.sample.schema.compose.Text
import dev.keliver.sample.schema.protocol.guest.SampleSchemaProtocolWidgetSystemFactory
import dev.keliver.sample.shared.HostFavoritesStore
import dev.keliver.sample.shared.SampleAppService
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json

/**
 * Guest-side entry point. Kotlin/JS compiles this into the Zipline
 * bundle; the bundle's `main()` runs once in QuickJS as soon as the
 * host calls `treehouseAppFactory.create(...)`.
 *
 * The single responsibility of `main()` here is binding the
 * `SampleAppService` impl under the name `"app"` so the host's
 * `TreehouseApp.Spec.create()` can take() it.
 */
public fun main() {
  val zipline = Zipline.get()
  // Take the host's generic HTTP transport (bound as "http"). Tolerant of
  // absence so the same bundle still runs on a host that didn't bind it.
  HttpBridge.provider = runCatching { zipline.take<HostHttpProvider>("http") }.getOrNull()
  FavoritesBridge.store = runCatching { zipline.take<HostFavoritesStore>("favorites") }.getOrNull()
  zipline.bind<SampleAppService>("app", SampleAppServiceImpl())
}

/** Holds the host HTTP transport so a screen can build a [KeliverHttp]. */
private object HttpBridge {
  var provider: HostHttpProvider? = null
}

/** Holds the host-owned "database" service. */
private object FavoritesBridge {
  var store: HostFavoritesStore? = null
}

private class SampleAppServiceImpl : SampleAppService {

  // The lifecycle is what wires the guest's @Composable tree into
  // Keliver's protocol layer. Required arguments:
  //
  //  - `protocolWidgetSystemFactory` is the generated factory from
  //    `:shared-protocol-guest` — it knows how to wrap the codegen'd
  //    `Box`/`Text` composables into mutations that travel across
  //    the Zipline boundary.
  //
  //  - `json` MUST carry serializers for every @Property type used
  //    in @Composable functions. Our schema has only `String text`,
  //    which is built-in, so the default Json is fine. If you add
  //    enums or @Serializable value types as @Property, register
  //    them in `serializersModule` here.
  //
  //  - `widgetVersion = 1U` — bump if you ship a schema change that
  //    older hosts shouldn't render (Treehouse handles graceful
  //    fallback via `requiredWidgetVersion`).
  override val appLifecycle: StandardAppLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = SampleSchemaProtocolWidgetSystemFactory,
    json = Json,
    widgetVersion = 1U,
  )

  override fun launch(): ZiplineTreehouseUi {
    val ui = object : TreehouseUi {
      @Composable
      override fun Show() {
        // The repository combines the API (keliver-http) and the host
        // "database" (HostFavoritesStore) — both taken at startup in main().
        val repo = remember {
          val provider = HttpBridge.provider
          val store = FavoritesBridge.store
          if (provider != null && store != null) {
            WorkoutsRepository(KeliverHttp(provider), store)
          } else {
            null
          }
        }
        if (repo == null) {
          Column { Text(text = "(host services not bound)") }
        } else {
          // STYLE B (recommended): events channel + presenter + UI in 3 lines.
          // To see STYLE A instead, replace this block with:
          //     WorkoutsScreenInline(repo)
          val events = remember { MutableSharedFlow<WorkoutsEvent>(extraBufferCapacity = 16) }
          val model = WorkoutsPresenter(events, repo)
          WorkoutsScreen(model) { events.tryEmit(it) }
        }
      }
    }
    return ui.asZiplineTreehouseUi(appLifecycle)
  }
}
