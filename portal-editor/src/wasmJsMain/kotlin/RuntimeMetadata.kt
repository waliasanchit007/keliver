/*
 * Copyright (C) 2026 Square, Inc.
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Widget/schema version hosted by this editor's in-browser composition. */
internal const val EDITOR_WIDGET_VERSION: Int = 1

internal data class AppRuntimeMetadata(
  val keliverVersion: String,
  val widgetVersion: Int,
)

internal sealed interface RuntimeMetadataLoad {
  data object Loading : RuntimeMetadataLoad
  data object Undeclared : RuntimeMetadataLoad
  data class Declared(val appRuntime: AppRuntimeMetadata) : RuntimeMetadataLoad
  data class Unavailable(val reason: String) : RuntimeMetadataLoad
}

internal enum class RuntimeCompatibilityStatus {
  Match,
  KeliverSkew,
  WidgetMismatch,
}

internal data class RuntimeCompatibility(
  val status: RuntimeCompatibilityStatus,
  val editor: AppRuntimeMetadata,
  val app: AppRuntimeMetadata,
)

internal fun parseRuntimeMetadata(raw: String): RuntimeMetadataLoad =
  runCatching {
    val runtimeElement = Json.parseToJsonElement(raw).jsonObject["appRuntime"]
    if (runtimeElement == null || runtimeElement is JsonNull) {
      return RuntimeMetadataLoad.Undeclared
    }
    val runtime = runtimeElement.jsonObject
    val keliverVersion = runtime.getValue("keliverVersion").jsonPrimitive.content
    val widgetVersion = runtime.getValue("widgetVersion").jsonPrimitive.int
    require(keliverVersion.isNotBlank()) { "appRuntime.keliverVersion is blank" }
    require(widgetVersion > 0) { "appRuntime.widgetVersion is not positive" }
    RuntimeMetadataLoad.Declared(
      AppRuntimeMetadata(keliverVersion, widgetVersion),
    )
  }.getOrElse { RuntimeMetadataLoad.Unavailable(it.message ?: "invalid response") }

internal fun compareRuntimeMetadata(
  editor: AppRuntimeMetadata,
  app: AppRuntimeMetadata,
): RuntimeCompatibility =
  RuntimeCompatibility(
    status = when {
      editor.widgetVersion != app.widgetVersion -> RuntimeCompatibilityStatus.WidgetMismatch
      editor.keliverVersion != app.keliverVersion -> RuntimeCompatibilityStatus.KeliverSkew
      else -> RuntimeCompatibilityStatus.Match
    },
    editor = editor,
    app = app,
  )
