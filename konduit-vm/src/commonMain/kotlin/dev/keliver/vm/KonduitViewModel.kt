/*
 * Copyright (C) 2026 Konduit contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.keliver.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Base class for guest-side view models. Owns a [viewModelScope] that cancels
 * when [onCleared] runs (which the [konduitViewModel] Compose helper invokes
 * automatically when the hosting `@Composable` leaves the tree).
 *
 * Mental model: matches Android's `ViewModel` API surface as closely as the
 * Konduit guest environment allows. The differences vs. Android:
 *
 *  - No configuration-change survival — the guest re-runs the whole bundle.
 *  - No `NavBackStackEntry`-scoped lifetime — the scope is tied to the
 *    `@Composable`, not to a navigator stack entry.
 *  - No DI integration — pass dependencies through the factory lambda.
 *
 * Subclass and put your business logic + state inside; the host services live
 * in the constructor.
 *
 * ```
 * class QuotesViewModel(
 *     private val quotes: HostQuotesProvider,
 * ) : KonduitViewModel() {
 *     val state = MutableStateFlow<List<Quote>>(emptyList())
 *     init {
 *         viewModelScope.launch {
 *             state.value = quotes.getQuotes(filter = null)
 *         }
 *     }
 * }
 * ```
 */
public abstract class KonduitViewModel {
  private val supervisor: Job = SupervisorJob()

  /**
   * Coroutine scope that survives recomposition and cancels when [onCleared]
   * runs. Default dispatcher is [Dispatchers.Main], which in the Konduit guest
   * maps to the Zipline dispatcher (QuickJS thread).
   */
  public val viewModelScope: CoroutineScope = CoroutineScope(supervisor + Dispatchers.Main)

  /**
   * Callback invoked by the framework when the hosting `@Composable` leaves
   * the tree (or when an upstream `key` change triggers VM rebuild). Cancels
   * [viewModelScope]. Override to add cleanup; always call `super.onCleared()`.
   *
   * `protected` so adopters don't accidentally cancel a VM's scope from
   * outside the class — the [konduitViewModel] helper calls through the
   * [clearInternal] trampoline instead.
   */
  protected open fun onCleared() {
    supervisor.cancel()
  }

  /**
   * Framework-internal trampoline so the inline [konduitViewModel] helper can
   * trigger [onCleared] without violating the `protected` visibility contract.
   * Not part of the public API; do not call directly.
   */
  @PublishedApi
  internal fun clearInternal() {
    onCleared()
  }
}

/**
 * Compose entry point. Constructs the view model on first call, returns the
 * same instance across recompositions, and runs [KonduitViewModel.onCleared]
 * when the hosting `@Composable` leaves the tree (or when [key] changes).
 *
 * The factory lambda is the dependency-wiring point — pass host service
 * handles, your `KonduitHttp` instance, etc.
 *
 * [key] is an optional cache-buster: when it changes between recompositions
 * the existing VM is `onCleared`'d and the [factory] runs again. Useful for
 * screens parameterized by an upstream value (a `userId`, a list filter, etc.)
 * that should produce a fresh VM when the value changes.
 *
 * ```
 * @Composable
 * override fun Content(navigator: Navigator, userId: String) {
 *     // VM rebuilds when userId changes
 *     val vm = konduitViewModel(key = userId) {
 *         QuotesViewModel(userId, HostQuotesProviderBridge.instance!!)
 *     }
 *     val quotes by vm.state.collectAsState()
 *     // …
 * }
 * ```
 */
@Composable
public inline fun <reified VM : KonduitViewModel> konduitViewModel(
  key: Any? = null,
  crossinline factory: () -> VM,
): VM {
  val vm = remember(VM::class, key) { factory() }
  DisposableEffect(vm) {
    onDispose { vm.clearInternal() }
  }
  return vm
}
