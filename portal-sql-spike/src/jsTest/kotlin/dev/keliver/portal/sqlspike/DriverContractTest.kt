package dev.keliver.portal.sqlspike

import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** In-memory "host" standing in for the Zipline-bound real one. */
private class FakeHost : HostSqlDriverService {
  private var nextId = 1L
  private val table = mutableListOf<SqlRow>()

  override suspend fun execute(sql: String, args: List<String?>): SqlRows {
    delay(1) // force real suspension across the bridge
    return when {
      sql.startsWith("INSERT") -> {
        table += SqlRow(listOf((nextId++).toString(), args[0], args[1]))
        SqlRows(rowsAffected = 1)
      }
      sql.startsWith("SELECT") -> SqlRows(rows = table.toList())
      else -> SqlRows()
    }
  }
}

class DriverContractTest {
  @Test
  fun typedQueryRoundTripsOverSuspendingSinglePayloadWire() = runTest {
    val queries = WorkoutQueries(PortalSqlDriver(FakeHost()))

    queries.insert("Bench Press", 3)
    queries.insert("Deadlift", 5)

    val all = queries.selectAll().awaitAsList()
    assertEquals(
      listOf(Workout(1, "Bench Press", 3), Workout(2, "Deadlift", 5)),
      all,
    )
  }
}
