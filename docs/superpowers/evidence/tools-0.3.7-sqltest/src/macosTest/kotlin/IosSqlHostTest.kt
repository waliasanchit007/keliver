package sqltest

import dev.keliver.portal.sql.SqlStatement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class IosSqlHostTest {
  private fun fresh() = IosSqlHost("sqltest-${Random.nextLong().toULong()}.db")

  @Test fun createInsertSelect() = runTest {
    val db = fresh()
    db.execute("CREATE TABLE item(id INTEGER PRIMARY KEY, name TEXT, qty INTEGER, note TEXT)", emptyList())
    assertEquals(1, db.execute("INSERT INTO item(name, qty, note) VALUES (?, ?, ?)", listOf("Oat milk", "24", null)).rowsAffected)
    assertEquals(1, db.execute("INSERT INTO item(name, qty, note) VALUES (?, ?, ?)", listOf("Lids", "15", "8oz")).rowsAffected)
    val rows = db.execute("SELECT name, qty, note FROM item ORDER BY id", emptyList()).rows.map { it.values }
    assertEquals(listOf(listOf("Oat milk", "24", null), listOf("Lids", "15", "8oz")), rows)
  }

  @Test fun updateAndDeleteReportChanges() = runTest {
    val db = fresh()
    db.execute("CREATE TABLE t(v TEXT)", emptyList())
    db.executeBatch(listOf(SqlStatement("INSERT INTO t VALUES (?)", listOf("a")), SqlStatement("INSERT INTO t VALUES (?)", listOf("b"))))
    assertEquals(2, db.execute("UPDATE t SET v = ?", listOf("c")).rowsAffected)
    assertEquals(2, db.execute("DELETE FROM t", emptyList()).rowsAffected)
    assertEquals("0", db.execute("SELECT COUNT(*) FROM t", emptyList()).rows.single().values.single())
  }

  @Test fun aFailingBatchRollsBack() = runTest {
    val db = fresh()
    db.execute("CREATE TABLE t(v TEXT NOT NULL)", emptyList())
    assertFailsWith<IllegalStateException> {
      db.executeBatch(listOf(SqlStatement("INSERT INTO t VALUES (?)", listOf("kept?")), SqlStatement("INSERT INTO t VALUES (?)", listOf(null))))
    }
    assertEquals("0", db.execute("SELECT COUNT(*) FROM t", emptyList()).rows.single().values.single())
  }

  @Test fun dataSurvivesReopening() = runTest {
    val name = "sqltest-persist-${Random.nextLong().toULong()}.db"
    IosSqlHost(name).apply {
      execute("CREATE TABLE t(v TEXT)", emptyList())
      execute("INSERT INTO t VALUES (?)", listOf("persisted"))
    }
    assertEquals("persisted", IosSqlHost(name).execute("SELECT v FROM t", emptyList()).rows.single().values.single())
  }

  @Test fun badSqlIsAnErrorNotACrash() = runTest {
    assertFailsWith<IllegalStateException> { fresh().execute("SELEKT nonsense", emptyList()) }
  }

  @Test fun emptySqlIsNoRowsNotACrash() = runTest {
    val db = fresh()
    assertEquals(0, db.execute("", emptyList()).rows.size)
    assertEquals(0, db.execute("  -- only a comment", emptyList()).rows.size)
  }
}
