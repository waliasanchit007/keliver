package dev.keliver.portaldevice.host

/**
 * U22. What this host is allowed to load, decided BEFORE anything is fetched.
 *
 * Two hosts are built from these sources:
 *
 *  * the **generic development host** shipped inside keliver-portal-tools. It is
 *    built with `-Pkeliver.devOnlyHost=true`, carries no trust key, and belongs
 *    to nobody: it must never be handed a production bundle, because there is
 *    no identity it could check one against.
 *  * an **app-specific production host**, built by an adopter with their own
 *    portal's public key embedded. It verifies signatures and is the only thing
 *    that may run prod mode.
 *
 * The rule this type exists to enforce: a request for production mode is either
 * honoured *with* signature verification or refused with a message that says
 * what to do. It is never quietly downgraded — the previous behaviour logged a
 * warning and continued with NO_SIGNATURE_CHECKS, which turns "verify this
 * bundle" into "load anything" at exactly the moment verification matters.
 */
public sealed interface HostTrust {
  /** Dev bundles are unsigned by design; the dev cache is kept separate. */
  public data object DevelopmentUnsigned : HostTrust

  /** Production, with the embedded key to verify against. */
  public data class ProductionVerified(val publicKeyHex: String) : HostTrust

  /** Production was asked for and must not happen. Nothing may be fetched. */
  public data class Refused(val message: String) : HostTrust
}

/**
 * @param prodMode the caller asked for production (`--es mode prod`).
 * @param devOnlyHost this binary is the generic development host.
 * @param publicKeyHex the embedded trust key, or null when none is bundled.
 */
public fun decideHostTrust(
  prodMode: Boolean,
  devOnlyHost: Boolean,
  publicKeyHex: String?,
): HostTrust {
  if (!prodMode) return HostTrust.DevelopmentUnsigned

  if (devOnlyHost) {
    return HostTrust.Refused(
      "This is the generic keliver development host: it is development-only and " +
        "cannot run production mode. It ships with no portal identity, so it has " +
        "nothing to verify a signed bundle against. Build your app's own device " +
        "host with your portal's public key embedded (see host/README.md, " +
        "\"Your own production host\"), or drop --es mode prod to use the " +
        "development route against serveDevelopmentZipline.",
    )
  }

  val key = publicKeyHex?.trim().orEmpty()
  if (key.isEmpty()) {
    return HostTrust.Refused(
      "Production mode requires an embedded portal public key and this build has " +
        "none. Rebuild the host on a machine whose portal store holds " +
        "keys/ed25519.pub (see host/README.md). Refusing to load a production " +
        "bundle without signature verification.",
    )
  }
  if (!key.matches(HEX)) {
    return HostTrust.Refused(
      "The embedded portal public key is not a 64-character hex Ed25519 key " +
        "(got ${key.length} character(s)), so no manifest can be verified against " +
        "it. Rebuild the host from a portal store with a valid keys/ed25519.pub. " +
        "Refusing to load a production bundle without signature verification.",
    )
  }
  return HostTrust.ProductionVerified(key)
}

/**
 * An Ed25519 public key is exactly 32 bytes — 64 hex characters. The bound
 * matters: without it a truncated `ed25519.pub` returned ProductionVerified
 * here and then threw inside `decodeHex()` in `onCreate`, so a malformed key
 * crashed the host instead of reaching the refusal screen. Rejecting it here
 * means the normal refusal path runs, before anything is fetched or loaded.
 */
private val HEX = Regex("^[0-9a-fA-F]{64}$")
