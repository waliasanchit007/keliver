/*
 * The iOS side of Keliver's data-layer wire (HostSqlDriver): ONE dumb SQLite
 * executor, the twin of the Android host's AndroidSqlHost. The guest owns the
 * schema, queries and migrations (they ship OTA in the bundle); this class
 * never knows what tables exist. The database lives in Application Support,
 * so the guest's data survives a restart.
 */
@file:OptIn(ExperimentalForeignApi::class)

package @@PACKAGE@@

import dev.keliver.portal.sql.HostSqlDriver
import dev.keliver.portal.sql.SqlRow
import dev.keliver.portal.sql.SqlRows
import dev.keliver.portal.sql.SqlStatement
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import keliver.sqlite3.SQLITE_DONE
import keliver.sqlite3.SQLITE_NULL
import keliver.sqlite3.SQLITE_OK
import keliver.sqlite3.SQLITE_ROW
import cnames.structs.sqlite3
import keliver.sqlite3.sqlite3_bind_null
import keliver.sqlite3.sqlite3_bind_text
import keliver.sqlite3.sqlite3_changes
import keliver.sqlite3.sqlite3_column_count
import keliver.sqlite3.sqlite3_column_text
import keliver.sqlite3.sqlite3_column_type
import keliver.sqlite3.sqlite3_errmsg
import keliver.sqlite3.sqlite3_exec
import keliver.sqlite3.sqlite3_finalize
import keliver.sqlite3.sqlite3_open
import keliver.sqlite3.sqlite3_prepare_v2
import keliver.sqlite3.sqlite3_step
import cnames.structs.sqlite3_stmt

class IosSqlHost(fileName: String = "portal-app.db") : HostSqlDriver {
  // One connection, one statement at a time: calls are serialized here.
  private val lock = Mutex()
  private val db: CPointer<sqlite3> = open(fileName)

  override suspend fun execute(sql: String, args: List<String?>): SqlRows =
    withContext(Dispatchers.Default) { lock.withLock { run(sql, args) } }

  override suspend fun executeBatch(statements: List<SqlStatement>): SqlRows =
    withContext(Dispatchers.Default) {
      lock.withLock {
        exec("BEGIN")
        var affected = 0L
        try {
          statements.forEach { affected += run(it.sql, it.args).rowsAffected }
          exec("COMMIT")
        } catch (e: Throwable) {
          runCatching { exec("ROLLBACK") }
          throw e
        }
        SqlRows(rowsAffected = affected)
      }
    }

  private fun run(sql: String, args: List<String?>): SqlRows = memScoped {
    val out = alloc<CPointerVar<sqlite3_stmt>>()
    check(sqlite3_prepare_v2(db, sql, -1, out.ptr, null) == SQLITE_OK) { "sqlite: ${message()} in: $sql" }
    val stmt = out.value!!
    try {
      args.forEachIndexed { i, a ->
        if (a == null) sqlite3_bind_null(stmt, i + 1) else sqlite3_bind_text(stmt, i + 1, a, -1, SQLITE_TRANSIENT)
      }
      val rows = mutableListOf<SqlRow>()
      while (true) {
        when (sqlite3_step(stmt)) {
          SQLITE_ROW -> rows += SqlRow(
            (0 until sqlite3_column_count(stmt)).map { c ->
              if (sqlite3_column_type(stmt, c) == SQLITE_NULL) null
              else sqlite3_column_text(stmt, c)?.reinterpret<ByteVar>()?.toKString()
            },
          )
          SQLITE_DONE -> break
          else -> error("sqlite: ${message()} in: $sql")
        }
      }
      val verb = sql.trimStart().take(6).uppercase()
      val affected = if (verb == "INSERT" || verb == "UPDATE" || verb == "DELETE") sqlite3_changes(db).toLong() else 0L
      SqlRows(rows = rows, rowsAffected = affected)
    } finally {
      sqlite3_finalize(stmt)
    }
  }

  private fun exec(sql: String) {
    check(sqlite3_exec(db, sql, null, null, null) == SQLITE_OK) { "sqlite: ${message()} in: $sql" }
  }

  private fun message(): String = sqlite3_errmsg(db)?.toKString() ?: "unknown error"

  private companion object {
    // SQLite copies bound text before sqlite3_bind_text returns.
    val SQLITE_TRANSIENT = (-1L).toCPointer<CFunction<(COpaquePointer?) -> Unit>>()

    fun open(fileName: String): CPointer<sqlite3> = memScoped {
      val dir = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .first() as String
      NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
      val out = alloc<CPointerVar<sqlite3>>()
      val path = "$dir/$fileName"
      check(sqlite3_open(path, out.ptr) == SQLITE_OK) { "sqlite: could not open $path" }
      out.value!!
    }
  }
}
