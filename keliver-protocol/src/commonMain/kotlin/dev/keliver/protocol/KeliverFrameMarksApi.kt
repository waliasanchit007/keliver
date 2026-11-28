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
package dev.keliver.protocol

/**
 * Denotes the cached-frame marker reader ([FrameMarks], [CachedFrameMark]). It exists for NF5's
 * recorder and its field set is expected to change in A3, so it is not stable across versions.
 *
 * @suppress
 */
@RequiresOptIn("The cached-frame marker reader is internal to NF5 and not stable across versions")
public annotation class KeliverFrameMarksApi
