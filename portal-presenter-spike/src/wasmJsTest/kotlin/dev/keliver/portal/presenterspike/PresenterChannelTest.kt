/*
 * Copyright (C) 2026 Keliver contributors.
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
package dev.keliver.portal.presenterspike

import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs IN wasm in ChromeHeadless — proves the serialized state/action channel. */
class PresenterChannelTest {
  @Test
  fun stateAndActionsCrossTheBoundaryAsJson() {
    val p = CounterPresenter()
    assertEquals("""{"title":"Cart","count":0,"total":"$0.0"}""", presenterStateJson(p))
    presenterActionJson(p, "add")
    val after = presenterActionJson(p, "add")
    assertEquals("""{"title":"Cart","count":2,"total":"$99.98"}""", after)
    assertEquals("""{"title":"Cart","count":1,"total":"$49.99"}""", presenterActionJson(p, "remove"))
  }
}
