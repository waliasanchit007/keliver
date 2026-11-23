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
package dev.keliver.portal.sql

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** SQLDelight-generated-shape queries — the same pattern app logic/ uses. */
private class TapQueries(private val driver: SqlDriver) {
  suspend fun createTable() {
    driver.execute(null, "CREATE TABLE IF NOT EXISTS taps (at TEXT)", 0, null).await()
  }

  suspend fun insert(at: String) {
    driver.execute(1, "INSERT INTO taps (at) VALUES (?)", 1) { bindString(0, at) }.await()
  }

  fun count(): ExecutableQuery<Long> = object : ExecutableQuery<Long>({ c -> c.getLong(0)!! }) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(2, "SELECT COUNT(*) FROM taps", mapper, 0, null)
  }

  fun all(): ExecutableQuery<String> = object : ExecutableQuery<String>({ c -> c.getString(0)!! }) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(3, "SELECT at FROM taps", mapper, 0, null)
  }
}

class DriverContractTest {
  @Test fun typedQueriesOverTheWire() = runTest {
    val q = TapQueries(PortalSqlDriver(FakeSqlHost()))
    q.createTable()
    q.insert("t1")
    q.insert("t2")
    assertEquals(2L, q.count().awaitAsOne())
    assertEquals(listOf("t1", "t2"), q.all().awaitAsList())
  }

  @Test fun batchIsSingleCallTransaction() = runTest {
    val host = FakeSqlHost()
    val rows = host.executeBatch(
      listOf(
        SqlStatement("CREATE TABLE taps (at TEXT)"),
        SqlStatement("INSERT INTO taps (at) VALUES (?)", listOf("a")),
        SqlStatement("INSERT INTO taps (at) VALUES (?)", listOf("b")),
      ),
    )
    assertEquals(2L, rows.rowsAffected)
    assertEquals(1, host.execute("SELECT COUNT(*) FROM taps", emptyList()).rows.size)
  }
}
