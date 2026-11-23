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
package dev.keliver.portaldevice.host

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portal.sql.SqlRow
import dev.keliver.portal.sql.SqlRows
import dev.keliver.portal.sql.SqlStatement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * V2 M7 — the Android side of the data-layer wire: ONE dumb SQLite executor.
 * The guest owns schema/queries/migrations (they ship OTA in the bundle);
 * this class never knows what tables exist.
 */

class AndroidSqlHost(context: Context) : HostSqlDriver {
  private val db: SQLiteDatabase =
    SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("portal-app.db").apply { parentFile?.mkdirs() }, null)

  override suspend fun execute(sql: String, args: List<String?>): SqlRows = withContext(Dispatchers.IO) {
    val trimmed = sql.trim()
    if (trimmed.startsWith("SELECT", ignoreCase = true) || trimmed.startsWith("PRAGMA", ignoreCase = true)) {
      db.rawQuery(sql, args.map { it ?: "" }.toTypedArray()).use { c ->
        val rows = mutableListOf<SqlRow>()
        while (c.moveToNext()) {
          rows += SqlRow((0 until c.columnCount).map { i -> if (c.isNull(i)) null else c.getString(i) })
        }
        SqlRows(rows = rows)
      }
    } else {
      db.compileStatement(sql).use { stmt ->
        args.forEachIndexed { i, a -> if (a == null) stmt.bindNull(i + 1) else stmt.bindString(i + 1, a) }
        val affected = when {
          trimmed.startsWith("INSERT", ignoreCase = true) -> if (stmt.executeInsert() >= 0) 1L else 0L
          trimmed.startsWith("UPDATE", ignoreCase = true) ||
            trimmed.startsWith("DELETE", ignoreCase = true) -> stmt.executeUpdateDelete().toLong()
          else -> {
            stmt.execute()
            0L
          }
        }
        SqlRows(rowsAffected = affected)
      }
    }
  }

  override suspend fun executeBatch(statements: List<SqlStatement>): SqlRows = withContext(Dispatchers.IO) {
    var affected = 0L
    db.beginTransaction()
    try {
      statements.forEach { affected += execute(it.sql, it.args).rowsAffected }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    SqlRows(rowsAffected = affected)
  }
}
