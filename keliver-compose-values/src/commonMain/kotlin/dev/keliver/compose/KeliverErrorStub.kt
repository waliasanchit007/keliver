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
package dev.keliver.compose

/**
 * Marks a generated error stub (MS4-07): a declaration that exists only so a guest's call to a Compose name Keliver does
 * not generate fails with a message instead of "unresolved reference". Every stub is `DeprecationLevel.ERROR`, so no
 * compiled code can call one; the build lists this marker in `apiValidation.nonPublicMarkers`, which keeps the stubs out
 * of the API dumps (a later PR that generates the name would otherwise show as a removal).
 *
 * @suppress
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class KeliverErrorStub
