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
package dev.keliver.portal.sql

import app.cash.zipline.ZiplineService
import kotlinx.serialization.Serializable

/**
 * V2 M7 — the data-layer WIRE (capability "HostSqlDriver@1"): hosts implement
 * ONE dumb SQL executor; guests own schema, queries, and migrations as pure
 * Kotlin that ships OTA. Rows cross as a single serializable payload (the
 * keliver-http U1 dodge). Batch = the transaction primitive.
 */
public interface HostSqlDriver : ZiplineService {
  public suspend fun execute(sql: String, args: List<String?>): SqlRows

  /** Runs all statements in ONE transaction (all-or-nothing). */
  public suspend fun executeBatch(statements: List<SqlStatement>): SqlRows
}

public const val HOST_SQL_CAPABILITY: String = "HostSqlDriver@1"

@Serializable
public data class SqlStatement(val sql: String, val args: List<String?> = emptyList())

@Serializable
public data class SqlRows(
  val rows: List<SqlRow> = emptyList(),
  val rowsAffected: Long = 0,
)

@Serializable
public data class SqlRow(val values: List<String?>)
