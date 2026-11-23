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
/**
 * web-spike = konduit's OWN portal-editor executable: the reusable shell
 * (:portal-editor, runPortalEditor) + this repo's app (AppLibPreview — Field
 * Notes / settings presenters; AppLibFlows — the Field Notes flow). A consumer
 * app's editor is the same one-liner with its own entries — that substitution
 * is the whole of "per-app preview". See docs/ROADMAP.md item ② and #13 F2.
 *
 * runPortalEditor + the entries are all in the root package (the shell across
 * the module boundary, the app in this module), so none needs an import.
 *
 * The flavour (web-spike/build.gradle) picks the entry: this repo's sample app,
 * or none for the tools bundle's generic editor.
 */
fun main() = runPortalEditor(flavorEntry, flows = flavorFlows)
