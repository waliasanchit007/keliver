/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.gateb

import androidx.compose.runtime.Composable
import app.cash.zipline.Zipline
import dev.keliver.compose.gatea.protocol.guest.KeliverComposeGateAProtocolWidgetSystemFactory
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kotlinx.serialization.json.Json

public fun main() {
  Zipline.get().bind<GatebAppService>("app", GatebAppServiceImpl())
}

private class GatebAppServiceImpl : GatebAppService {
  // The generated group's root protocol (it includes keliver-material, layout and lazy layout).
  override val appLifecycle: StandardAppLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverComposeGateAProtocolWidgetSystemFactory,
    json = Json,
    widgetVersion = 1U,
  )

  override fun launch(mode: String): ZiplineTreehouseUi {
    val ui = object : TreehouseUi {
      @Composable
      override fun Show() {
        AnimationBench(mode)
      }
    }
    return ui.asZiplineTreehouseUi(appLifecycle)
  }
}
