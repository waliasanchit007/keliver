/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.sample.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.keliver.sample.schema.widget.SampleSchemaWidgetSystem
import dev.keliver.sample.shared.SampleAppService
import dev.keliver.treehouse.TreehouseApp
import dev.keliver.treehouse.TreehouseContentSource
import dev.keliver.treehouse.composeui.TreehouseContent

/**
 * Shared mount-point composable. Both `:host-android` and the iOS
 * shell render this — the only platform-specific bit is how the
 * `TreehouseApp<SampleAppService>` got constructed (each host has
 * its own `TreehouseAppFactory(...)` call site because of HTTP
 * client + cache plumbing).
 *
 *   - `widgetSystem` is the codegen'd union of all widgets in the
 *     schema, wrapped around our `CmpWidgetFactory`. `remember { }`
 *     so it survives recomposition — `TreehouseContent` watches it
 *     by reference and a new instance every recomposition would
 *     tear down the guest session.
 *
 *   - `contentSource` calls back into the guest's `SampleAppService`
 *     to ask it which `ZiplineTreehouseUi` to mount. The standard
 *     shape is `{ it.launch() }`.
 */
@Composable
public fun SampleHostApp(treehouseApp: TreehouseApp<SampleAppService>) {
  MaterialTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.fillMaxSize()) {
        val widgetSystem = remember {
          SampleSchemaWidgetSystem(CmpWidgetFactory)
        }
        val contentSource = remember {
          object : TreehouseContentSource<SampleAppService> {
            override fun get(app: SampleAppService) = app.launch()
          }
        }
        TreehouseContent(
          treehouseApp = treehouseApp,
          widgetSystem = widgetSystem,
          contentSource = contentSource,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}
