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
import dev.keliver.portal.render.AppFlowEntry
import dev.keliver.portal.render.AppPreviewEntry
import dev.keliver.portal.render.ScreenPreview

/**
 * The tools bundle's GENERIC editor, served for any app without its own
 * editor. It carries none of this repo's sample app: compiled adapters are
 * looked up by component name and would draw the sample's components in place
 * of an app's same-named ones, and the sample's presenters, personas and flows
 * would offer (and run, on Live) another app's logic for an app whose screens
 * share their names. Opaque components stay placeholders; an app's own editor
 * (keliver-new-editor.sh) brings its presenters. See web-spike/build.gradle.
 */
internal object GenericPreviewEntry : AppPreviewEntry {
  override val label: String = "app"
  override val screens: Map<String, ScreenPreview> = emptyMap()
}

internal val flavorEntry: AppPreviewEntry = GenericPreviewEntry

internal val flavorFlows: AppFlowEntry? = null
