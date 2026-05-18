/*
 * Copyright (C) 2026 Konduit contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.konduit.vm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KonduitViewModelTest {
  private class TestVM : KonduitViewModel()

  @Test
  fun viewModelScope_isActive_initially() {
    val vm = TestVM()
    assertTrue(vm.viewModelScope.isActive)
  }

  @Test
  fun clearInternal_cancelsScope() {
    val vm = TestVM()
    vm.clearInternal()
    assertFalse(vm.viewModelScope.isActive)
  }

  @Test
  fun launchedCoroutine_cancelsOnClear() = runTest {
    val vm = TestVM()
    val cancelled = CompletableDeferred<Unit>()
    val job = vm.viewModelScope.launch(Dispatchers.Unconfined) {
      try {
        awaitCancellation()
      } finally {
        cancelled.complete(Unit)
      }
    }
    assertTrue(job.isActive)
    vm.clearInternal()
    withContext(Dispatchers.Unconfined) {
      cancelled.await()
    }
    assertTrue(job.isCancelled)
  }

  @Test
  fun subclass_canOverride_onCleared_and_super_still_cancels() {
    var subclassCalled = false
    val vm = object : KonduitViewModel() {
      override fun onCleared() {
        subclassCalled = true
        super.onCleared()
      }
    }
    vm.clearInternal()
    assertTrue(subclassCalled, "subclass onCleared should run via the trampoline")
    assertFalse(vm.viewModelScope.isActive, "super.onCleared should still cancel the scope")
  }

  @Test
  fun subclass_can_skip_super_to_keep_scope_active() {
    // Edge case: subclass overrides without calling super. Documented as
    // bad practice but the framework shouldn't crash; verify the scope
    // stays active (which is the natural consequence of not cancelling).
    var subclassCalled = false
    val vm = object : KonduitViewModel() {
      override fun onCleared() {
        subclassCalled = true
        // Deliberately not calling super.onCleared()
      }
    }
    vm.clearInternal()
    assertTrue(subclassCalled)
    assertTrue(
      vm.viewModelScope.isActive,
      "without super.onCleared the supervisor stays active — adopter chose not to cancel",
    )
  }
}
