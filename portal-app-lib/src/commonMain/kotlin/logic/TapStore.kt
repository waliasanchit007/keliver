package dev.keliver.portalpublished.logic

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

/**
 * HAND-OWNED data layer (M7, design §8): schema + typed queries are pure
 * guest Kotlin that ships OTA in the signed bundle — a new table or query is
 * a publish, not an app-store release. The host only executes SQL.
 */
class TapStore(private val driver: SqlDriver) {
  suspend fun createTable() {
    driver.execute(null, "CREATE TABLE IF NOT EXISTS taps (at TEXT)", 0, null).await()
  }

  suspend fun insert(at: String) {
    driver.execute(1, "INSERT INTO taps (at) VALUES (?)", 1) { bindString(0, at) }.await()
  }

  suspend fun count(): Long = countQuery().awaitAsOne()

  private fun countQuery(): ExecutableQuery<Long> = object : ExecutableQuery<Long>({ c -> c.getLong(0)!! }) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(2, "SELECT COUNT(*) FROM taps", mapper, 0, null)
  }
}
