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
package dev.keliver.treehouse

/**
 * Denotes NF5's frame fold ([ChangeFolder] and its rule and result types). It is written for the
 * cached first frame's recorder (A3c) and store (A3b), and its shape is expected to change until
 * those land, so it is not stable across versions.
 *
 * @suppress
 */
@RequiresOptIn("The frame fold is internal to NF5 and not stable across versions")
public annotation class KeliverFrameFoldApi
