/*
 * Copyright (C) 2023 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.redwood.testapp.browser

import dev.konduit.compose.RedwoodComposition
import dev.konduit.compose.WindowAnimationFrameClock
import dev.konduit.layout.dom.HTMLElementRedwoodLayoutWidgetFactory
import dev.konduit.lazylayout.dom.HTMLElementRedwoodLazyLayoutWidgetFactory
import dev.konduit.ui.basic.dom.HTMLElementRedwoodUiBasicWidgetFactory
import dev.konduit.widget.asRedwoodView
import com.example.redwood.testapp.presenter.HttpClient
import com.example.redwood.testapp.presenter.TestApp
import com.example.redwood.testapp.presenter.TestContext
import com.example.redwood.testapp.widget.TestSchemaWidgetSystem
import kotlin.js.json
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.plus
import org.w3c.dom.HTMLElement
import org.w3c.fetch.RequestInit

fun main() {
  val content = document.getElementById("content") as HTMLElement

  @OptIn(DelicateCoroutinesApi::class)
  val composition = RedwoodComposition(
    scope = GlobalScope + WindowAnimationFrameClock,
    view = content.asRedwoodView(),
    widgetSystem = TestSchemaWidgetSystem(
      TestSchema = HtmlWidgetFactory(document),
      RedwoodUiBasic = HTMLElementRedwoodUiBasicWidgetFactory(document),
      RedwoodLayout = HTMLElementRedwoodLayoutWidgetFactory(document),
      RedwoodLazyLayout = HTMLElementRedwoodLazyLayoutWidgetFactory(document),
    ),
  )

  val client = HttpClient { url, headers ->
    val jsHeaders = json(*headers.entries.map { it.toPair() }.toTypedArray())
    val response = window.fetch(url, RequestInit(headers = jsHeaders)).await()
    response.text().await()
  }

  val context = TestContext(client)
  composition.setContent {
    TestApp(context)
  }
}
