package @@PACKAGE@@

/**
 * Whether this host may load anything. It is production-only, so the only
 * question is whether it has a key to verify bundles against. There is no
 * unverified fallback: no key, no fetch.
 */
sealed interface ProductionTrust {
  data class Verified(val publicKeyHex: String) : ProductionTrust
  data class Refused(val message: String) : ProductionTrust
}

fun decideProductionTrust(publicKeyHex: String?): ProductionTrust {
  val key = publicKeyHex?.trim().orEmpty()
  if (key.isEmpty()) {
    return ProductionTrust.Refused(
      "This build has no embedded portal public key (PORTAL_PUBLIC_KEY_HEX in HostConfig.kt), so no bundle can be " +
        "verified. Nothing was loaded.",
    )
  }
  if (!key.matches(Regex("^[0-9a-fA-F]{64}$"))) {
    return ProductionTrust.Refused(
      "The embedded portal public key is not a 64-hex-digit Ed25519 key (got ${key.length} " +
        "character(s)). Nothing was loaded.",
    )
  }
  return ProductionTrust.Verified(key)
}
