/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.bridge

/**
 * A call through an app's bridge failed: no answer within its limit, the app's `fail`, or a
 * throw (named by its class only). The message is `KeliverBridge: <bridge>.<function>: <reason>`
 * on one line, and there is never a cause, so that line is all that crosses to the guest.
 *
 * Zipline sends a throwable as its stack-trace text and the guest gets a `ZiplineException`;
 * the generated guest code turns it back into this with [fromZiplineMessage].
 */
public class BridgeCallFailed internal constructor(message: String) : RuntimeException(message) {
  public constructor(bridge: String, function: String, reason: String) :
    this("$PREFIX$bridge.$function: ${reason.oneLine()}")

  public companion object {
    internal const val PREFIX: String = "KeliverBridge: "

    // How a JVM or Kotlin/Native host's stackTraceToString() starts (the qualified name);
    // the jvmTest and iosTest RealTextTest check it against the real text. R8 must keep the
    // name: keliver-bridge's jar carries META-INF/proguard/keliver-bridge.pro.
    private const val THROWN: String = "dev.keliver.bridge.BridgeCallFailed: "

    /**
     * The failure a `ZiplineException`'s [message] carries, rebuilt from its first line alone
     * (no host stack frames), or null if it is not a [BridgeCallFailed].
     */
    public fun fromZiplineMessage(message: String?): BridgeCallFailed? {
      val first = message?.lineSequence()?.first() ?: return null
      if (!first.startsWith(THROWN + PREFIX)) return null
      return BridgeCallFailed(first.removePrefix(THROWN))
    }
  }
}

private val lineBreaks = Regex("\\s*[\\r\\n]+\\s*")

internal fun String.oneLine(): String = replace(lineBreaks, " ").trim()
