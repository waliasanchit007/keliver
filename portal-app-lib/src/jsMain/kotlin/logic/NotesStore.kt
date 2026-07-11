package dev.keliver.portalpublished.logic

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.keliver.portalpublished.screens.Note

/**
 * HAND-OWNED data layer (M7): the Field Notes table + typed queries are pure
 * guest Kotlin shipped OTA in the signed bundle. rowid doubles as the note id
 * the feed's openNote action carries to the detail screen (P2).
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

  suspend fun byId(id: String): Note? = byIdQuery(id).awaitAsOneOrNull()

  private fun countQuery(): ExecutableQuery<Long> = object : ExecutableQuery<Long>({ c -> c.getLong(0)!! }) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(3, "SELECT COUNT(*) FROM notes", mapper, 0, null)
  }

  private fun noteOf(c: SqlCursor): Note = object : Note {
    override val id: String = c.getString(0) ?: ""
    override val title: String = c.getString(1) ?: ""
    override val body: String = c.getString(2) ?: ""
    override val time: String = c.getString(3) ?: ""
  }

  private fun allQuery(): ExecutableQuery<Note> = object : ExecutableQuery<Note>({ c -> noteOf(c) }) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(4, "SELECT rowid, title, body, time FROM notes ORDER BY rowid DESC", mapper, 0, null)
  }

  private fun byIdQuery(id: String): ExecutableQuery<Note> = object : ExecutableQuery<Note>({ c -> noteOf(c) }) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(5, "SELECT rowid, title, body, time FROM notes WHERE rowid = ?", mapper, 1) { bindString(0, id) }
  }
}
