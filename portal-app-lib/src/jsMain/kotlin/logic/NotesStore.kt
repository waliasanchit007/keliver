package dev.keliver.portalpublished.logic

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.keliver.portalpublished.screens.Note

/**
 * HAND-OWNED data layer (M7): the Field Notes table + typed queries are pure
 * guest Kotlin shipped OTA in the signed bundle. A `List<Note>` query is the
 * data side of the P1-B per-item Repeat binding — the rows map straight onto
 * the `note.*` fields the feed renders.
 */
class NotesStore(private val driver: SqlDriver) {
  suspend fun createTable() {
    driver.execute(null, "CREATE TABLE IF NOT EXISTS notes (title TEXT, body TEXT, time TEXT)", 0, null).await()
  }

  suspend fun insert(title: String, body: String, time: String) {
    driver.execute(1, "INSERT INTO notes (title, body, time) VALUES (?, ?, ?)", 3) {
      bindString(0, title)
      bindString(1, body)
      bindString(2, time)
    }.await()
  }

  suspend fun clear() {
    driver.execute(2, "DELETE FROM notes", 0, null).await()
  }

  suspend fun count(): Long = countQuery().awaitAsOne()

  suspend fun all(): List<Note> = allQuery().awaitAsList()

  private fun countQuery(): ExecutableQuery<Long> = object : ExecutableQuery<Long>({ c -> c.getLong(0)!! }) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(3, "SELECT COUNT(*) FROM notes", mapper, 0, null)
  }

  private fun allQuery(): ExecutableQuery<Note> = object : ExecutableQuery<Note>({ c ->
    object : Note {
      override val title: String = c.getString(0) ?: ""
      override val body: String = c.getString(1) ?: ""
      override val time: String = c.getString(2) ?: ""
    }
  }) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(4, "SELECT title, body, time FROM notes ORDER BY rowid DESC", mapper, 0, null)
  }
}
