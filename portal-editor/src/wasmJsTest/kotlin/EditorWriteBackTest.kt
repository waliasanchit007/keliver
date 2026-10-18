import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K3 last review round: how the editor reads the relay's answer to an op
 * batch (a network error or timeout is never a stuck "in flight"; an HTTP 200
 * it cannot read is unknown, not refused) and the write-back status on the
 * change stream (a refused or failed write-back is shown once per version).
 */
class EditorWriteBackTest {
  @Test fun anOpsReplyIsReadByStatusAndBody() {
    assertEquals(OpsReply.Accepted(7), opsReply(200, """{"ok":true,"version":7}"""))
    assertEquals(OpsReply.Rejected("no"), opsReply(200, """{"ok":false,"version":6,"error":"no"}"""))
    assertEquals(OpsReply.Rebase, opsReply(409, """{"ok":false,"version":9,"error":"stale"}"""))
    assertEquals(OpsReply.Rejected("bad"), opsReply(400, """{"ok":false,"version":6,"error":"bad"}"""))
    // A 200 the editor cannot read: the relay may have applied it — unknown, never rolled back.
    assertEquals(OpsReply.Unknown, opsReply(200, "<html>proxy</html>"))
    assertEquals(OpsReply.Unknown, opsReply(204, ""))
    // No HTTP status at all: the request never completed (error, timeout, abort).
    assertEquals(OpsReply.NetworkError, opsReply(null, null))
    assertEquals(OpsReply.NetworkError, opsReply(0, ""))
    assertTrue(opsReply(500, "boom") is OpsReply.Rejected)
  }

  @Test fun theWriteBackStatusIsReadFromAChangeEvent() {
    val data = """{"version":4,"tokens":0,"writeBack":{"version":4,"outcome":"refused","written":false,"message":"this change could not be written to Home.kt without losing code: x \"y\"; undo it or make it in code"}}"""
    val wb = writeBackOf(data)!!
    assertEquals(4, wb.version)
    assertEquals("refused", wb.outcome)
    assertEquals(false, wb.written)
    assertTrue("Home.kt" in wb.message && "\"y\"" in wb.message, wb.message)
    assertNull(writeBackOf("""{"version":4,"tokens":0}"""))
    assertNull(writeBackOf("not json"))
  }

  @Test fun aRefusalIsWarnedOncePerVersionAndClearedByAWrite() {
    val w = WriteBackWarnings()
    val refused = WriteBackNotice(4, "refused", false, "could not be written")
    assertEquals("could not be written", w.next(refused))
    assertNull(w.next(refused), "the same refusal is not shown twice")
    assertTrue(w.showing)
    assertNull(w.next(WriteBackNotice(5, "surgical", true, "written")))
    assertTrue(!w.showing, "a later write clears the warning")
    assertEquals("failed", w.next(WriteBackNotice(6, "failed", false, "failed")))
    // The relay sends its current status with every event: none means none is current.
    assertNull(w.next(null))
    assertTrue(!w.showing, "an event without a status clears the warning")
  }

  @Test fun aReadOfTheFileClearsTheWarningAndADiscardIsToldOnce() {
    val w = WriteBackWarnings()
    w.next(WriteBackNotice(4, "refused", false, "could not be written"))
    assertTrue(w.showing)
    // The file was read with nothing unwritten: cleared, nothing to say.
    assertNull(w.next(WriteBackNotice(5, "external", true, "home.kt was re-read")))
    assertTrue(!w.showing)
    w.next(WriteBackNotice(6, "refused", false, "could not be written"))
    // Read while the document was ahead: said once — but the document IS the file now (no "Not written").
    assertEquals("discarded it", w.next(WriteBackNotice(7, "discarded", false, "discarded it")))
    assertTrue(!w.showing, "after a discard the document is the file")
    assertNull(w.next(WriteBackNotice(7, "discarded", false, "discarded it")))
  }
}
