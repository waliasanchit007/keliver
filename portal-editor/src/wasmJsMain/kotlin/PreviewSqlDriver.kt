import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * P3-12: the browser preview implementation of the SQL capability — a
 * sqldelight [SqlDriver] over the in-memory [PreviewSqlHost], so the app's
 * REAL stores/presenters run their REAL query strings unchanged in the editor.
 * Only the executor differs from the device (HostSqlDriver over Zipline).
 */
class PreviewSqlDriver(private val host: PreviewSqlHost) : SqlDriver {

  private class Binder : SqlPreparedStatement {
    val args = mutableListOf<String?>()
    private fun set(index: Int, v: String?) {
      while (args.size <= index) args.add(null)
      args[index] = v
    }
    override fun bindBytes(index: Int, bytes: ByteArray?) = set(index, bytes?.decodeToString())
    override fun bindBoolean(index: Int, boolean: Boolean?) = set(index, boolean?.toString())
    override fun bindDouble(index: Int, double: Double?) = set(index, double?.toString())
    override fun bindLong(index: Int, long: Long?) = set(index, long?.toString())
    override fun bindString(index: Int, string: String?) = set(index, string)
  }

  private class RowsCursor(private val rows: List<List<String?>>) : SqlCursor {
    private var i = -1
    override fun next(): QueryResult<Boolean> = QueryResult.Value(++i < rows.size)
    private fun cell(index: Int): String? = rows.getOrNull(i)?.getOrNull(index)
    override fun getString(index: Int): String? = cell(index)
    override fun getLong(index: Int): Long? = cell(index)?.toLongOrNull()
    override fun getBytes(index: Int): ByteArray? = cell(index)?.encodeToByteArray()
    override fun getDouble(index: Int): Double? = cell(index)?.toDoubleOrNull()
    override fun getBoolean(index: Int): Boolean? = cell(index)?.toBooleanStrictOrNull()
  }

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<Long> {
    val b = Binder().apply { binders?.invoke(this) }
    host.execute(sql, b.args)
    return QueryResult.Value(0L)
  }

  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<R> {
    val b = Binder().apply { binders?.invoke(this) }
    return mapper(RowsCursor(host.execute(sql, b.args)))
  }

  override fun newTransaction(): QueryResult<Transacter.Transaction> =
    throw UnsupportedOperationException("preview driver: transactions not supported")
  override fun currentTransaction(): Transacter.Transaction? = null
  override fun addListener(vararg queryKeys: String, listener: Query.Listener) {}
  override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {}
  override fun notifyListeners(vararg queryKeys: String) {}
  override fun close() {}
}
