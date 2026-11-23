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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The relay's tests never resolve the developer's real store. This repo's
 * keliver.portal.json names `~/.keliver-portal`, so without the test task's
 * PORTAL_STORE (portal-relay/build.gradle) the first test touching the relay's
 * top-level state claimed it — writing its owner marker — and a later run from
 * another checkout of the repo exited 70 on the ownership refusal.
 */
class HermeticTestStoreTest {
  @Test fun theTestJvmsStoreIsUnderTheBuildDirectory() {
    val store = assertNotNull(System.getenv("PORTAL_STORE"), "the test task sets PORTAL_STORE").let(::File).canonicalFile
    val build = File("build").canonicalFile
    assertTrue(store.toPath().startsWith(build.toPath()), "PORTAL_STORE=$store is not under $build")
    val realHome = File(System.getProperty("user.home"), ".keliver-portal").canonicalFile
    assertTrue(!store.toPath().startsWith(realHome.toPath()), "PORTAL_STORE=$store is inside $realHome")
  }
}
