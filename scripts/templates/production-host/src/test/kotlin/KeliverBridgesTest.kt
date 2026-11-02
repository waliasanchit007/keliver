package @@PACKAGE@@

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B5 (docs/BRIDGE_CODEGEN_DESIGN.md, section "B5"): YOUR app's bridges in KeliverConfig. A list
 * the host must not serve is refused when the config is made, before anything is looked up; the
 * gate itself (BundleIndex.kt) is tested with the relay's BridgeGateTest.
 */
class KeliverBridgesTest {
  private val shape = "cd5829702ffe49be"
  private val noBind: (KeliverBridgeScope) -> Unit = {}

  private fun config(vararg bridges: KeliverBridgeBinding) =
    KeliverConfig(bundleServer = "https://bundles.example.com", publicKeyHex = null, bridges = bridges.toList())

  private fun refused(vararg bridges: KeliverBridgeBinding): String = try {
    config(*bridges)
    fail("refused: ${bridges.map { it.name }}")
    error("unreachable")
  } catch (e: IllegalArgumentException) {
    e.message!!
  }

  @Test
  fun aConfigWithoutBridgesServesNone() {
    assertEquals(emptyList<KeliverBridgeBinding>(), KeliverConfig("https://bundles.example.com", null).bridges)
    assertEquals(emptyMap<String, HostBridge>(), servedBridgesOf(config().bridges.map { it.declared }))
  }

  @Test
  fun aConfigKeepsTheAppsBridgesAsDeclared() {
    val c = config(KeliverBridgeBinding("ShopActions", 2, shape, noBind), KeliverBridgeBinding("Other", 1, null, noBind))
    assertEquals(listOf("ShopActions", "Other"), c.bridges.map { it.name })
    assertEquals(
      mapOf("ShopActions" to HostBridge(2, shape), "Other" to HostBridge(1, null)),
      servedBridgesOf(c.bridges.map { it.declared }),
    )
  }

  @Test
  fun aConfigRefusesAListTheHostMustNotServe() {
    for ((why, binding) in listOf(
      "not a bridge name" to KeliverBridgeBinding("Shop@2", 1, null, noBind),
      "binds a service of that name itself" to KeliverBridgeBinding("HostHttp", 1, null, noBind),
      "version 0" to KeliverBridgeBinding("Shop", 0, null, noBind),
      "not a generated SHAPE" to KeliverBridgeBinding("Shop", 1, "not-a-shape", noBind),
    )) {
      val message = refused(binding)
      assertTrue(message, message.startsWith("KeliverConfig.bridges: "))
      assertTrue(message, why in message)
    }
    val twice = refused(KeliverBridgeBinding("Shop", 1, null, noBind), KeliverBridgeBinding("Shop", 2, null, noBind))
    assertTrue(twice, "declared twice" in twice)
  }
}
