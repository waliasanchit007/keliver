@file:OptIn(ExperimentalForeignApi::class)

package @@PACKAGE@@

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.write

/**
 * [text] and its newline on stdout, which `xcrun simctl launch --console-pty` captures, in one
 * write(2). println writes the text and then the newline, so a line printed on another thread
 * can land between them: "…knheap=-KeliverHost: codeLoadSuccess …", one line that is neither.
 * Every host line goes through here.
 */
internal fun consoleLine(text: String) {
  val bytes = "$text\n".encodeToByteArray()
  bytes.usePinned { pinned ->
    var at = 0
    while (at < bytes.size) {
      val n = write(1, pinned.addressOf(at), (bytes.size - at).convert()).toInt()
      if (n > 0) at += n else if (n < 0 && errno == EINTR) continue else return
    }
  }
}
