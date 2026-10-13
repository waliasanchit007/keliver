package @@PACKAGE@@

/**
 * Whether this host may load anything. It is production-only, so the only
 * question is whether it has a key to verify bundles against. There is no
 * unverified fallback: no key, no fetch.
 *
 * W8, key rotation: a host trusts this app's key and, during a rotation, the
 * NEXT key too (at most two; docs/KEY_ROTATION.md). Bundles signed by either
 * load. The first key names the Zipline cache and the rollback floor's home.
 */
sealed interface ProductionTrust {
  /** [publicKeysHex]: this app's key, then the next one during a rotation; lower-case hex. */
  data class Verified(val publicKeysHex: List<String>) : ProductionTrust {
    val publicKeyHex: String get() = publicKeysHex.first()
  }
  data class Refused(val message: String) : ProductionTrust
}

fun decideProductionTrust(publicKeyHex: String?, nextPublicKeyHex: String? = null): ProductionTrust {
  val key = publicKeyHex?.trim().orEmpty()
  val next = nextPublicKeyHex?.trim().orEmpty()
  if (key.isEmpty()) {
    return ProductionTrust.Refused(
      "This build has no embedded portal public key (PORTAL_PUBLIC_KEY_HEX in HostConfig.kt), so no bundle can be " +
        "verified. Nothing was loaded.",
    )
  }
  if (!key.matches(HEX_KEY)) {
    return ProductionTrust.Refused(
      "The embedded portal public key is not a 64-hex-digit Ed25519 key (got ${key.length} " +
        "character(s)). Nothing was loaded.",
    )
  }
  if (next.isNotEmpty() && !next.matches(HEX_KEY)) {
    return ProductionTrust.Refused(
      "The embedded next portal public key (PORTAL_NEXT_PUBLIC_KEY_HEX in HostConfig.kt) is not a 64-hex-digit Ed25519 key " +
        "(got ${next.length} character(s)). Nothing was loaded.",
    )
  }
  if (next.equals(key, ignoreCase = true)) {
    return ProductionTrust.Refused(
      "The embedded next portal public key (PORTAL_NEXT_PUBLIC_KEY_HEX in HostConfig.kt) is the current key again; it must be " +
        "the NEXT key of a rotation, or empty. Nothing was loaded.",
    )
  }
  return ProductionTrust.Verified(listOfNotNull(key.lowercase(), next.lowercase().takeIf { it.isNotEmpty() }))
}

private val HEX_KEY = Regex("^[0-9a-fA-F]{64}$")
