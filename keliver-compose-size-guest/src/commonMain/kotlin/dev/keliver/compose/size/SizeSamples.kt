/*
 * Copyright (C) 2026 Keliver contributors.
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
package dev.keliver.compose.size

import app.cash.zipline.ZiplineService
import dev.keliver.treehouse.AppService
import dev.keliver.treehouse.ZiplineTreehouseUi

/** The size harness's guest (MS4-03): one same-source sample screen by name ([SAMPLE_ORDERS], [SAMPLE_CARD] or [SAMPLE_ITEM]). */
interface SizeSamples :
  AppService,
  ZiplineService {
  fun launch(name: String): ZiplineTreehouseUi
}

const val SAMPLE_ORDERS = "orders"
const val SAMPLE_CARD = "card"
const val SAMPLE_ITEM = "item"

/** The samples' data, shared by the guest and the hosts' native copies. */
object SizeSampleData {
  val orders = listOf(
    "Order 1042" to "Out for delivery",
    "Order 1041" to "Packed",
    "Order 1039" to "Delivered on Monday",
    "Order 1036" to "Refund in progress",
  )
  const val CARD_TITLE = "A long item title that will not fit on one line"
  const val CARD_QUANTITY = "Qty 12"
  val tags = listOf("Fragile", "Bulk", "In stock", "Ships today", "Gift wrap", "Returnable")
}
