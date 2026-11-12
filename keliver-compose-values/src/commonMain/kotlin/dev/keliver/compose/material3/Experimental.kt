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
package dev.keliver.compose.material3

/**
 * As `androidx.compose.material3.ExperimentalMaterial3Api`, so a screen's `@OptIn` compiles in
 * both copies. The generated stubs do not require it yet (Gate B, part b: copy Compose's opt-in
 * markers onto the stubs).
 */
@RequiresOptIn("This material API is experimental and is likely to change or to be removed in the future.")
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalMaterial3Api
