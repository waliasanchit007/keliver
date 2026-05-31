/*
 * Copyright (C) 2026 Keliver contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.keliver.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeliverNavControllerTest {

  /**
   * Sample sealed route — mirrors the API adopters will reach for. Kept
   * non-`@Serializable` here because serialization isn't required by
   * keliver-nav itself; adopters opt in for deep-link support.
   */
  private sealed interface TestRoute {
    data object Home : TestRoute
    data class Detail(val id: String) : TestRoute
    data class Search(val query: String) : TestRoute
  }

  // Use `internal` to mirror what the production factory does.
  private fun controller() = KeliverNavController<TestRoute>(TestRoute.Home)

  @Test
  fun starts_at_root_route() {
    val nav = controller()
    assertEquals(TestRoute.Home, nav.current)
    assertEquals(listOf<TestRoute>(TestRoute.Home), nav.backstack)
    assertFalse(nav.canPop)
  }

  @Test
  fun navigate_pushes_to_top() {
    val nav = controller()
    nav.navigate(TestRoute.Detail("42"))

    assertEquals(TestRoute.Detail("42"), nav.current)
    assertEquals(listOf(TestRoute.Home, TestRoute.Detail("42")), nav.backstack)
    assertTrue(nav.canPop)
  }

  @Test
  fun pop_removes_top_when_above_root() {
    val nav = controller()
    nav.navigate(TestRoute.Detail("42"))

    val popped = nav.pop()

    assertTrue(popped)
    assertEquals(TestRoute.Home, nav.current)
    assertFalse(nav.canPop)
  }

  @Test
  fun pop_is_noop_at_root() {
    val nav = controller()

    val popped = nav.pop()

    assertFalse(popped)
    assertEquals(TestRoute.Home, nav.current)
    assertEquals(1, nav.backstack.size)
  }

  @Test
  fun popUntil_pops_until_predicate_matches() {
    val nav = controller()
    nav.navigate(TestRoute.Search("kotlin"))
    nav.navigate(TestRoute.Detail("1"))
    nav.navigate(TestRoute.Detail("2"))

    val matched = nav.popUntil { it is TestRoute.Search }

    assertTrue(matched)
    assertEquals(TestRoute.Search("kotlin"), nav.current)
    assertEquals(
      listOf(TestRoute.Home, TestRoute.Search("kotlin")),
      nav.backstack,
    )
  }

  @Test
  fun popUntil_returns_false_when_predicate_never_matches() {
    val nav = controller()
    nav.navigate(TestRoute.Detail("1"))
    nav.navigate(TestRoute.Detail("2"))

    val matched = nav.popUntil { it is TestRoute.Search }

    assertFalse(matched)
    // Stops at root (couldn't pop any further).
    assertEquals(TestRoute.Home, nav.current)
    assertEquals(1, nav.backstack.size)
  }

  @Test
  fun popUntil_returns_true_immediately_when_top_already_matches() {
    val nav = controller()
    nav.navigate(TestRoute.Detail("1"))

    val matched = nav.popUntil { it is TestRoute.Detail }

    assertTrue(matched)
    // No pop happened — same entries.
    assertEquals(listOf(TestRoute.Home, TestRoute.Detail("1")), nav.backstack)
  }

  @Test
  fun replaceAll_clears_stack_and_sets_new_root() {
    val nav = controller()
    nav.navigate(TestRoute.Detail("1"))
    nav.navigate(TestRoute.Detail("2"))

    nav.replaceAll(TestRoute.Search("after"))

    assertEquals(TestRoute.Search("after"), nav.current)
    assertEquals(listOf<TestRoute>(TestRoute.Search("after")), nav.backstack)
    assertFalse(nav.canPop)
  }

  @Test
  fun each_navigate_assigns_a_unique_entry_id() {
    val nav = controller()

    nav.navigate(TestRoute.Detail("x"))
    val firstId = nav.entries.last().id
    nav.pop()
    nav.navigate(TestRoute.Detail("x"))
    val secondId = nav.entries.last().id

    // Same route value, different entry id — guarantees fresh
    // SaveableStateHolder state on the re-navigation.
    assertNotEquals(firstId, secondId)
  }

  @Test
  fun deep_stack_pop_preserves_order() {
    val nav = controller()
    val pushes = listOf(
      TestRoute.Detail("1"),
      TestRoute.Detail("2"),
      TestRoute.Search("q"),
      TestRoute.Detail("3"),
    )
    pushes.forEach { nav.navigate(it) }

    repeat(2) { nav.pop() }

    assertEquals(
      listOf(TestRoute.Home, TestRoute.Detail("1"), TestRoute.Detail("2")),
      nav.backstack,
    )
  }
}
