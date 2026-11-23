/*
 * Copyright (C) 2026 Keliver contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.keliver.portal.sqlspike

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

/**
 * Hand-written in exactly the shape SQLDelight 2.x GENERATES for a .sq file —
 * proves the generated-code ↔ driver contract without the gradle plugin
 * (plugin integration = ordinary usage, validated at M7 start).
 */
data class Workout(val id: Long, val name: String, val sets: Long)

class WorkoutQueries(private val driver: SqlDriver) {
  fun selectAll(): ExecutableQuery<Workout> = SelectAllQuery { cursor ->
    Workout(
      id = cursor.getLong(0)!!,
      name = cursor.getString(1)!!,
      sets = cursor.getLong(2)!!,
    )
  }

  suspend fun insert(name: String, sets: Long) {
    driver.execute(
      identifier = 1,
      sql = "INSERT INTO workout (name, sets) VALUES (?, ?)",
      parameters = 2,
    ) {
      bindString(0, name)
      bindLong(1, sets)
    }.await()
  }

  private inner class SelectAllQuery<out T : Any>(
    mapper: (SqlCursor) -> T,
  ) : ExecutableQuery<T>(mapper) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(
        identifier = 2,
        sql = "SELECT id, name, sets FROM workout",
        mapper = mapper,
        parameters = 0,
        binders = null,
      )
  }
}
