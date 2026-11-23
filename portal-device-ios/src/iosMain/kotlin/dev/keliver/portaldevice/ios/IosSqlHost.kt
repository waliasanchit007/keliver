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
package dev.keliver.portaldevice.ios

import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portal.sql.SqlRow
import dev.keliver.portal.sql.SqlRows
import dev.keliver.portal.sql.SqlStatement

/*
 * V2 M7/M9 — the iOS side of the data-layer wire. Parity with AndroidSqlHost;
 * the guest owns schema/queries (they ship OTA). This impl is in-memory for
 * the dev/overlay path (a real sqlite3 bridge is the production increment,
 * mirroring AndroidSqlHost's SQLiteDatabase); the wire is identical either way.
 */

class IosSqlHost : HostSqlDriver {
  private val rows = mutableListOf<List<String?>>()

  override suspend fun execute(sql: String, args: List<String?>): SqlRows {
    val s = sql.trim()
    return when {
      s.startsWith("CREATE TABLE", ignoreCase = true) -> SqlRows()
      s.startsWith("INSERT", ignoreCase = true) -> {
        rows.add(args)
        SqlRows(rowsAffected = 1)
      }
      s.startsWith("DELETE", ignoreCase = true) -> {
        val n = rows.size
        rows.clear()
        SqlRows(rowsAffected = n.toLong())
      }
      s.startsWith("SELECT COUNT", ignoreCase = true) -> SqlRows(rows = listOf(SqlRow(listOf(rows.size.toString()))))
      s.startsWith("SELECT", ignoreCase = true) -> SqlRows(rows = rows.map { SqlRow(it) })
      else -> SqlRows()
    }
  }

  override suspend fun executeBatch(statements: List<SqlStatement>): SqlRows {
    var affected = 0L
    statements.forEach { affected += execute(it.sql, it.args).rowsAffected }
    return SqlRows(rowsAffected = affected)
  }
}
