/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.console

import app.cash.zipline.ZiplineService

/**
 * Host-bound logging service. The guest calls [log] when it wants to
 * surface a message through the host's logging stack (Logcat on Android,
 * `os_log` / `NSLog` on iOS, the JVM logging framework, etc.).
 *
 * Adopters typically bind a single instance of [DefaultKeliverConsole]
 * (or a platform-specific subclass) at startup:
 *
 * ```
 * override suspend fun bindServices(treehouseApp, zipline) {
 *     bindWithTimeout {
 *         zipline.bind<KeliverConsole>("console", retain(DefaultKeliverConsole()))
 *     }
 *     // … other services
 * }
 * ```
 *
 * Level strings are uninterpreted by the framework — adopters can pass
 * any scheme they like (`"DEBUG"`, `"WARN"`, `"ERROR"`, or numeric
 * `"30"`/`"40"` for syslog parity). Platform-specific impls decide how
 * to route levels to the underlying logger.
 */
public interface KeliverConsole : ZiplineService {
  public fun log(level: String, message: String)
}

/**
 * Reference implementation that writes `"[$tag/$level] $message"` to
 * `println`. Suitable for development and as a starting point adopters
 * subclass when they want platform-specific routing
 * (`android.util.Log.d`, `os_log_with_type`, SLF4J, etc.).
 *
 * `println` lands on Logcat (via stdout redirect) on Android, on the
 * Xcode console on iOS / Kotlin Native, and on stdout for JVM tests.
 * That covers the "I just want to see what the guest is logging"
 * case; production deployments will want platform-specific routing.
 */
public open class DefaultKeliverConsole(
  private val tag: String = "Keliver",
) : KeliverConsole {
  override fun log(level: String, message: String) {
    output("[$tag/$level] $message")
  }

  /**
   * Override hook for tests + platform-specific routing. The default
   * implementation calls `println`; subclasses can route to
   * `android.util.Log`, `NSLog`, SLF4J, or anywhere else.
   */
  protected open fun output(line: String) {
    println(line)
  }
}
