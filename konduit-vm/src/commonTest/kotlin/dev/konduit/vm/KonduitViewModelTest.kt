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
  fun onCleared_cancelsScope() {
    val vm = TestVM()
    vm.onCleared()
    assertFalse(vm.viewModelScope.isActive)
  }

  @Test
  fun launchedCoroutine_cancelsOnCleared() = runTest {
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
    vm.onCleared()
    withContext(Dispatchers.Unconfined) {
      cancelled.await()
    }
    assertTrue(job.isCancelled)
  }

  @Test
  fun subclass_canOverride_onCleared() {
    var subclassCalled = false
    val vm = object : KonduitViewModel() {
      override fun onCleared() {
        subclassCalled = true
        super.onCleared()
      }
    }
    vm.onCleared()
    assertTrue(subclassCalled, "subclass onCleared should run")
    assertFalse(vm.viewModelScope.isActive, "super.onCleared should still cancel scope")
  }
}
