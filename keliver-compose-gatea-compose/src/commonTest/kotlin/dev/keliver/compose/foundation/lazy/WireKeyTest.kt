/*
 * Copyright (C) 2026 Square, Inc.
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
package dev.keliver.compose.foundation.lazy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WireKeyTest {
  private class Sku(val code: String) {
    override fun toString() = "Sku"
  }

  private data class Bin(val aisle: Int)

  private data class Shelf(val aisle: Int) {
    override fun toString() = "Bin(aisle=$aisle)"
  }

  @Test fun keysAreTyped() {
    assertNotEquals(wireKey(1), wireKey("1"))
    assertNotEquals(wireKey(1), wireKey(1L))
    // Two types that print the same stay distinct.
    assertNotEquals(wireKey(Bin(3)), wireKey(Shelf(3)))
  }

  @Test fun differentKeysThatPrintTheSameAreRefusedWithAMessage() {
    val sent = HashMap<String, Any>()
    checkWireKey(sent, wireKey(Sku("a")), Sku("a"))
    val e = assertFailsWith<IllegalArgumentException> { checkWireKey(sent, wireKey(Sku("b")), Sku("b")) }
    assertTrue("same toString()" in e.message!!, e.message)
  }

  @Test fun equalKeysAreLeftToCompose() {
    val sent = HashMap<String, Any>()
    checkWireKey(sent, wireKey(Bin(1)), Bin(1))
    checkWireKey(sent, wireKey(Bin(1)), Bin(1))
    assertEquals(1, sent.size)
  }
}
