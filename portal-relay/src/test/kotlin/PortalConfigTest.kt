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
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortalConfigTest {
  @Test fun missingConfigKeepsRuntimeUndeclared() {
    val repo = createTempDirectory("portal-config-").toFile()
    try {
      val config = loadPortalConfig(repo)

      assertNull(config.appRuntime)
      assertEquals("""{"appRuntime":null}""", config.runtimeMetadataJson())
    } finally {
      repo.deleteRecursively()
    }
  }

  @Test fun runtimeMetadataRoundTripsThroughConfigAndEndpointShape() {
    val repo = createTempDirectory("portal-config-").toFile()
    try {
      File(repo, "keliver.portal.json").writeText(
        """
        {
          "appRuntime": {
            "keliverVersion": "0.3.0",
            "widgetVersion": 1
          }
        }
        """.trimIndent(),
      )

      val config = loadPortalConfig(repo)

      assertEquals(AppRuntimeMetadata("0.3.0", 1), config.appRuntime)
      assertEquals(
        """{"appRuntime":{"keliverVersion":"0.3.0","widgetVersion":1}}""",
        config.runtimeMetadataJson(),
      )
    } finally {
      repo.deleteRecursively()
    }
  }

  @Test fun blankRuntimeVersionIsRejected() {
    val error = assertFailsWith<IllegalArgumentException> {
      AppRuntimeMetadata("", 1)
    }

    assertContains(error.message.orEmpty(), "keliverVersion")
  }

  @Test fun nonPositiveWidgetVersionIsRejected() {
    val error = assertFailsWith<IllegalArgumentException> {
      AppRuntimeMetadata("0.3.0", 0)
    }

    assertContains(error.message.orEmpty(), "widgetVersion")
  }

  @Test fun httpFixturesResolveInsideRepository() {
    val repo = createTempDirectory("portal-config-").toFile()
    try {
      val resolved = PortalConfig(httpFixturesDir = "fixtures/http")
        .resolvedHttpFixturesDir(repo)

      assertTrue(resolved.toPath().startsWith(repo.canonicalFile.toPath()))
      assertEquals(File(repo, "fixtures/http").canonicalFile, resolved)
    } finally {
      repo.deleteRecursively()
    }
  }

  @Test fun httpFixturesCannotEscapeRepository() {
    val repo = createTempDirectory("portal-config-").toFile()
    try {
      val error = assertFailsWith<IllegalArgumentException> {
        PortalConfig(httpFixturesDir = "../outside").resolvedHttpFixturesDir(repo)
      }

      assertContains(error.message.orEmpty(), "inside the app repository")
    } finally {
      repo.deleteRecursively()
    }
  }
}
