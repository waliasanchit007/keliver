// Test-harness copies of dev.keliver:portal-sql's wire types (that artifact has
// no macOS variant). Same names and shapes; serialization/Zipline omitted.
package dev.keliver.portal.sql

interface HostSqlDriver {
  suspend fun execute(sql: String, args: List<String?>): SqlRows
  suspend fun executeBatch(statements: List<SqlStatement>): SqlRows
}
data class SqlStatement(val sql: String, val args: List<String?> = emptyList())
data class SqlRows(val rows: List<SqlRow> = emptyList(), val rowsAffected: Long = 0)
data class SqlRow(val values: List<String?>)
