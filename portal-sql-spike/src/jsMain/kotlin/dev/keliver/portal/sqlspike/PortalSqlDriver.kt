package dev.keliver.portal.sqlspike

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.serialization.Serializable

/**
 * The V2 wire shape (M7's `HostSqlDriver` Zipline service): one generic
 * suspend call, rows crossing as a SINGLE @Serializable payload (dodges the
 * U1 List<@Serializable>-return bind hang, same as keliver-http).
 */
interface HostSqlDriverService {
  suspend fun execute(sql: String, args: List<String?>): SqlRows
}

@Serializable
data class SqlRows(
  val rows: List<SqlRow> = emptyList(),
  val rowsAffected: Long = 0,
)

@Serializable
data class SqlRow(val values: List<String?>)

/** Collects bound parameters as strings (spike: TEXT affinity only). */
private class CollectingStatement : SqlPreparedStatement {
  val args = mutableListOf<String?>()
  private fun set(index: Int, v: String?) {
    while (args.size <= index) args.add(null)
    args[index] = v
  }
  override fun bindBoolean(index: Int, boolean: Boolean?) = set(index, boolean?.toString())
  override fun bindBytes(index: Int, bytes: ByteArray?) = set(index, bytes?.decodeToString())
  override fun bindDouble(index: Int, double: Double?) = set(index, double?.toString())
  override fun bindLong(index: Int, long: Long?) = set(index, long?.toString())
  override fun bindString(index: Int, string: String?) = set(index, string)
}

/** Async cursor over the single-payload rows. */
private class ListCursor(private val rows: List<SqlRow>) : SqlCursor {
  private var index = -1
  override fun next(): QueryResult<Boolean> = QueryResult.Value(++index < rows.size)
  private fun at(i: Int): String? = rows[index].values.getOrNull(i)
  override fun getBoolean(index: Int): Boolean? = at(index)?.toBooleanStrictOrNull()
  override fun getBytes(index: Int): ByteArray? = at(index)?.encodeToByteArray()
  override fun getDouble(index: Int): Double? = at(index)?.toDoubleOrNull()
  override fun getLong(index: Int): Long? = at(index)?.toLongOrNull()
  override fun getString(index: Int): String? = at(index)
}

/**
 * The guest-side SQLDelight driver: every statement becomes one suspend call
 * over the wire. QueryResult.AsyncValue carries the suspension into
 * SQLDelight's async machinery.
 */
class PortalSqlDriver(private val host: HostSqlDriverService) : SqlDriver {
  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<R> = QueryResult.AsyncValue {
    val stmt = CollectingStatement()
    binders?.invoke(stmt)
    val rows = host.execute(sql, stmt.args)
    mapper(ListCursor(rows.rows)).await()
  }

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<Long> = QueryResult.AsyncValue {
    val stmt = CollectingStatement()
    binders?.invoke(stmt)
    host.execute(sql, stmt.args).rowsAffected
  }

  override fun newTransaction(): QueryResult<Transacter.Transaction> =
    throw UnsupportedOperationException("transactions: M7 (executeBatch on the wire)")
  override fun currentTransaction(): Transacter.Transaction? = null
  override fun addListener(vararg queryKeys: String, listener: Query.Listener) {}
  override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {}
  override fun notifyListeners(vararg queryKeys: String) {}
  override fun close() {}
}
