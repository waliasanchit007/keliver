@file:OptIn(ExperimentalForeignApi::class)

package @@PACKAGE@@

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSLock
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.write

private val consoleLock = NSLock()

/**
 * [text] and its newline on stdout, which `xcrun simctl launch --console-pty` captures, whole.
 * println writes the text and then the newline, so a line printed on another thread can land
 * between them: "…knheap=-KeliverHost: codeLoadSuccess …", one line that is neither. Here the
 * line is one write(2), under a lock: a tty may copy a write in chunks and sleep between them
 * when its reader lags, so the lock, not the single write, keeps the host's lines apart. Every
 * host line goes through here; a library's own println (Zipline's, the runtime's) does not.
 */
internal fun consoleLine(text: String) {
  val bytes = "$text\n".encodeToByteArray()
  consoleLock.lock()
  try {
    bytes.usePinned { pinned ->
      var at = 0
      while (at < bytes.size) {
        val n = write(1, pinned.addressOf(at), (bytes.size - at).convert()).toInt()
        if (n > 0) at += n else if (n < 0 && errno == EINTR) continue else return
      }
    }
  } finally {
    consoleLock.unlock()
  }
}
