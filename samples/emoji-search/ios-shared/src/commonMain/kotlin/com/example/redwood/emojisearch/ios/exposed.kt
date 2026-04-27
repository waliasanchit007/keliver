/*
 * Copyright (C) 2022 Square, Inc.
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
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.example.redwood.emojisearch.ios

import dev.konduit.Modifier
import dev.konduit.treehouse.AppService
import dev.konduit.treehouse.Content
import dev.konduit.treehouse.TreehouseUIView
import dev.konduit.treehouse.TreehouseView
import dev.konduit.treehouse.bindWhenReady
import dev.konduit.ui.basic.protocol.host.RedwoodUiBasicHostProtocol
import dev.konduit.ui.basic.uiview.UIViewRedwoodUiBasicWidgetSystem
import dev.konduit.widget.WidgetSystem
import okio.Closeable

// Used to export types to Objective-C / Swift.
fun exposedTypes(
  emojiSearchLauncher: EmojiSearchLauncher,
  hostProtocol: RedwoodUiBasicHostProtocol,
  treehouseUIView: TreehouseUIView,
  widgetSystem: WidgetSystem<*>,
) {
  throw AssertionError()
}

fun basicWidgetSystem() = UIViewRedwoodUiBasicWidgetSystem()

fun modifier(): Modifier = Modifier

fun <A : AppService> bindWhenReady(
  content: Content,
  view: TreehouseView<*>,
): Closeable = content.bindWhenReady(view)
