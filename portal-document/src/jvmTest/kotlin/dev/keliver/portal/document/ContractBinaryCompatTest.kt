package dev.keliver.portal.document

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * portal-document is published: a JVM caller compiled against 0.3.x calls
 * [Contract]'s three-argument `copy` and constructor — and their defaulted
 * forms (`copy$default`, the synthetic constructor) when it named only some
 * arguments. K4 added [Contract.unlistedFieldsUnknown]; those signatures must
 * still exist, and keep the new field as a recompiled caller would.
 */
class ContractBinaryCompatTest {
  private val c = Contract::class.java
  private val map = Map::class.java
  private val list = List::class.java

  @Test fun theThreeArgumentCopyStillExistsAndKeepsTheNewField() {
    val open = Contract(fields = mapOf("a" to "Int"), unlistedFieldsUnknown = "why")
    val copy = c.getMethod("copy", map, list, map)
    val copied = copy.invoke(open, mapOf("b" to "String"), listOf("go"), emptyMap<String, String>()) as Contract
    assertEquals(Contract(mapOf("b" to "String"), listOf("go"), emptyMap(), "why"), copied)
    // `contract.copy(fields = x)` compiled against 0.3.x calls the static default stub.
    val stub = c.getMethod("copy\$default", c, map, list, map, Int::class.javaPrimitiveType, Any::class.java)
    val defaulted = stub.invoke(null, open, mapOf("b" to "String"), null, null, 0b110, null) as Contract
    assertEquals(Contract(mapOf("b" to "String"), emptyList(), emptyMap(), "why"), defaulted)
  }

  @Test fun theThreeArgumentConstructorsStillExist() {
    val plain = c.getConstructor(map, list, map).newInstance(mapOf("a" to "Int"), listOf("go"), emptyMap<String, String>())
    assertEquals(Contract(mapOf("a" to "Int"), listOf("go"), emptyMap(), null), plain)
    val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
    val defaulted = c.getConstructor(map, list, map, Int::class.javaPrimitiveType, marker)
      .newInstance(mapOf("a" to "Int"), null, null, 0b110, null)
    assertEquals(Contract(fields = mapOf("a" to "Int")), defaulted)
  }
}
