/*
 * V2 M7/M9 — the iOS side of the data-layer wire. Parity with AndroidSqlHost;
 * the guest owns schema/queries (they ship OTA). This impl is in-memory for
 * the dev/overlay path (a real sqlite3 bridge is the production increment,
 * mirroring AndroidSqlHost's SQLiteDatabase); the wire is identical either way.
 */
package dev.keliver.portaldevice.ios

import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portal.sql.SqlRow
import dev.keliver.portal.sql.SqlRows
import dev.keliver.portal.sql.SqlStatement

class IosSqlHost : HostSqlDriver {
  private val rows = mutableListOf<List<String?>>()

  override suspend fun execute(sql: String, args: List<String?>): SqlRows {
    val s = sql.trim()
    return when {
      s.startsWith("CREATE TABLE", ignoreCase = true) -> SqlRows()
      s.startsWith("INSERT", ignoreCase = true) -> { rows.add(args); SqlRows(rowsAffected = 1) }
      s.startsWith("DELETE", ignoreCase = true) -> { val n = rows.size; rows.clear(); SqlRows(rowsAffected = n.toLong()) }
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
