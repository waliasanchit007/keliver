/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.guest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.cash.zipline.Zipline
import dev.keliver.sample.schema.compose.Button
import dev.keliver.sample.schema.compose.Column
import dev.keliver.sample.schema.compose.Row
import dev.keliver.sample.schema.compose.Spacer
import dev.keliver.sample.schema.compose.Text
import dev.keliver.sample.schema.protocol.guest.SampleSchemaProtocolWidgetSystemFactory
import dev.keliver.sample.shared.SampleAppService
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
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
  zipline.bind<SampleAppService>("app", SampleAppServiceImpl())
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
        // Reads almost exactly like Compose: a Column of widgets, local
        // state via remember { mutableStateOf }, and a Button whose
        // onClick recomposes the label across the Zipline bridge.
        var count by remember { mutableStateOf(0) }
        Column {
          Text(text = "Hello, Keliver!")
          Spacer(height = 12)
          Row {
            Text(text = "Built with ")
            Text(text = "Compose-like widgets")
          }
          Spacer(height = 12)
          Button(text = "Tapped $count times", onClick = { count++ })
        }
      }
    }
    return ui.asZiplineTreehouseUi(appLifecycle)
  }
}
