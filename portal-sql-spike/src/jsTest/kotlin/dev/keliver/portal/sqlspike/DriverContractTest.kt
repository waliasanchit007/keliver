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
package dev.keliver.portal.sqlspike

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/** In-memory "host" standing in for the Zipline-bound real one. */
private class FakeHost : HostSqlDriverService {
  private var nextId = 1L
  private val table = mutableListOf<SqlRow>()

  override suspend fun execute(sql: String, args: List<String?>): SqlRows {
    delay(1) // force real suspension across the bridge
    return when {
      sql.startsWith("INSERT") -> {
        table += SqlRow(listOf((nextId++).toString(), args[0], args[1]))
        SqlRows(rowsAffected = 1)
      }
      sql.startsWith("SELECT") -> SqlRows(rows = table.toList())
      else -> SqlRows()
    }
  }
}

class DriverContractTest {
  @Test
  fun typedQueryRoundTripsOverSuspendingSinglePayloadWire() = runTest {
    val queries = WorkoutQueries(PortalSqlDriver(FakeHost()))

    queries.insert("Bench Press", 3)
    queries.insert("Deadlift", 5)

    val all = queries.selectAll().awaitAsList()
    assertEquals(
      listOf(Workout(1, "Bench Press", 3), Workout(2, "Deadlift", 5)),
      all,
    )
  }
}
