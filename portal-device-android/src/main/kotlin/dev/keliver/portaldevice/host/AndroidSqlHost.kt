/*
 * V2 M7 — the Android side of the data-layer wire: ONE dumb SQLite executor.
 * The guest owns schema/queries/migrations (they ship OTA in the bundle);
 * this class never knows what tables exist.
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
          else -> { stmt.execute(); 0L }
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
