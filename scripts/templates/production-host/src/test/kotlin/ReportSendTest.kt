package @@PACKAGE@@

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6's reports on the wire (reportHttpClient, postReport). The report client retries nothing, so
 * a report must never go out on a kept connection the collector has already closed: the host's
 * reports come in bursts (WS6: `screen-failed` and `crash-rollback` from one uncaught exception,
 * moments after `loaded`), and a collector that closes after each answer (an HTTP/1.0 server,
 * or one whose keep-alive has run out) would otherwise lose every report sent on its dead
 * connection. Found on the device: the in-process rollback's `crash-rollback` never arrived.
 */
class ReportSendTest {
  /** A collector that answers each POST with `HTTP/1.0 204` and closes, as Python's http.server does. */
  private class ClosingCollector : AutoCloseable {
    val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val url = "http://127.0.0.1:${socket.localPort}/report"
    private val acceptor = thread(isDaemon = true) {
      while (!socket.isClosed) {
        val s = runCatching { socket.accept() }.getOrNull() ?: break
        thread(isDaemon = true) { s.use(::answer) }
      }
    }

    private fun answer(s: Socket) {
      val input = s.getInputStream().buffered()
      val head = StringBuilder()
      while (!head.endsWith("\r\n\r\n")) {
        val c = input.read()
        if (c < 0) return
        head.append(c.toChar())
      }
      val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
      bodies += String(input.readNBytes(length), Charsets.UTF_8)
      s.getOutputStream().apply {
        write("HTTP/1.0 204 No Content\r\nServer: collector\r\n\r\n".toByteArray())
        flush()
      }
    }

    override fun close() = socket.close()
  }

  private val collector = ClosingCollector()
  private val client = reportHttpClient()

  @After fun stop() {
    collector.close()
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
  }

  private fun report(outcome: String, sequence: Long, detail: String = "", source: String? = "network") =
    KeliverReport("install", "production", 7, sequence, source, outcome, reportReason(outcome, detail), detail)

  /** Sends [reports] together (as one exception's do), and waits until each was answered or failed. */
  private fun send(vararg reports: KeliverReport): List<String?> {
    val failures = Collections.synchronizedList(mutableListOf<String?>())
    val done = CountDownLatch(reports.size)
    val counting = client.newBuilder().eventListener(object : okhttp3.EventListener() {
      override fun callEnd(call: okhttp3.Call) = done.countDown()
      override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) = done.countDown()
    }).build()
    reports.forEach { r -> postReport(counting, collector.url, r.toJson()) { failures += it } }
    assertTrue("the reports were not answered in time", done.await(15, TimeUnit.SECONDS))
    return failures
  }

  private fun crashRollbacks(): List<String> = collector.bodies.filter { "\"outcome\":\"crash-rollback\"" in it }

  /** C3/C4 on the device: `loaded`, then the throw's `screen-failed` and the rollback's `crash-rollback`. */
  @Test fun theInProcessRollbackSendsExactlyOneCrashRollbackReport() {
    assertEquals(listOf<String?>(), send(report("loaded", 2)))
    Thread.sleep(200) // the collector has closed the first connection by now
    val failures = send(
      report("screen-failed", 2, "Index 1 out of bounds for length 1"),
      report(CRASH_ROLLBACK_OUTCOME, 2, crashRollbackDetail(CrashStep.SetAside(2, 2, CrashKind.EXCEPTION), 2, crashGoingBackTo(1)), source = null),
    )
    assertEquals("reports not sent", listOf<String?>(), failures)
    assertEquals(3, collector.bodies.size)
    val cr = crashRollbacks()
    assertEquals(1, cr.size)
    assertTrue(cr.single(), "\"reason\":\"previous-good\"" in cr.single() && "\"sequence\":2" in cr.single())
  }

  /** C8 on the device: the app's own view, reported once. */
  @Test fun theAppViewSendsExactlyOneCrashRollbackReport() {
    assertEquals(listOf<String?>(), send(report("loaded", 4)))
    Thread.sleep(200)
    val failures = send(
      report("screen-failed", 4, "Index 1 out of bounds for length 1"),
      report(CRASH_ROLLBACK_OUTCOME, 4, crashRollbackDetail(CrashStep.SetAside(4, 2, CrashKind.EXCEPTION), 2, CRASH_TO_APP_VIEW), source = null),
    )
    assertEquals("reports not sent", listOf<String?>(), failures)
    val cr = crashRollbacks()
    assertEquals(1, cr.size)
    assertTrue(cr.single(), "\"reason\":\"app-view\"" in cr.single())
  }

  /** One after another, each moments after the last was answered on a connection the collector closed. */
  @Test fun reportsInARowAllArrive() {
    repeat(4) { i ->
      assertEquals("report $i not sent", listOf<String?>(), send(report("loaded", i + 1L)))
      Thread.sleep(100)
    }
    assertEquals(4, collector.bodies.size)
  }
}
