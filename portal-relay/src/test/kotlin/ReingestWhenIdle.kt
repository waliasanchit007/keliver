/**
 * [DocumentService.reingest] answers false while a write is still in flight
 * (its status can already be set while the write's task finishes), and the
 * relay retries (Relay.forceReingest). Tests retry the same way instead of
 * asserting the first answer.
 */
fun reingestWhenIdle(svc: DocumentService, timeoutMillis: Long = 5_000): Boolean {
  val deadline = System.currentTimeMillis() + timeoutMillis
  while (!svc.reingest()) {
    if (System.currentTimeMillis() > deadline) return false
    Thread.sleep(25)
  }
  return true
}
