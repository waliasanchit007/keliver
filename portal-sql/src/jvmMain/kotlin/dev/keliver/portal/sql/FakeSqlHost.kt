package dev.keliver.portal.sql

/**
 * In-memory single-table host for tests and the JVM preview tier. Understands
 * the tiny SQL subset the demo data layer uses (CREATE/INSERT/SELECT/UPDATE
 * against a key-value-with-rowid shape) — NOT a SQL engine; real hosts wrap
 * real SQLite.
 */
public class FakeSqlHost : HostSqlDriver {
  private val tables = mutableMapOf<String, MutableList<List<String?>>>()

  override suspend fun execute(sql: String, args: List<String?>): SqlRows {
    val s = sql.trim()
    return when {
      s.startsWith("CREATE TABLE", ignoreCase = true) -> {
        val name = Regex("CREATE TABLE(?: IF NOT EXISTS)?\\s+(\\w+)", RegexOption.IGNORE_CASE)
          .find(s)?.groupValues?.get(1) ?: "t"
        tables.getOrPut(name) { mutableListOf() }
        SqlRows()
      }
      s.startsWith("INSERT", ignoreCase = true) -> {
        val name = Regex("INSERT INTO\\s+(\\w+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1) ?: "t"
        tables.getOrPut(name) { mutableListOf() }.add(args)
        SqlRows(rowsAffected = 1)
      }
      s.startsWith("DELETE", ignoreCase = true) -> {
        val name = Regex("DELETE FROM\\s+(\\w+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1) ?: "t"
        val n = tables[name]?.size ?: 0
        tables[name]?.clear()
        SqlRows(rowsAffected = n.toLong())
      }
      s.startsWith("SELECT COUNT", ignoreCase = true) -> {
        val name = Regex("FROM\\s+(\\w+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1) ?: "t"
        SqlRows(rows = listOf(SqlRow(listOf((tables[name]?.size ?: 0).toString()))))
      }
      s.startsWith("SELECT", ignoreCase = true) -> {
        val name = Regex("FROM\\s+(\\w+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1) ?: "t"
        SqlRows(rows = tables[name].orEmpty().map { SqlRow(it) })
      }
      else -> SqlRows()
    }
  }

  override suspend fun executeBatch(statements: List<SqlStatement>): SqlRows {
    var affected = 0L
    statements.forEach { affected += execute(it.sql, it.args).rowsAffected }
    return SqlRows(rowsAffected = affected)
  }
}
