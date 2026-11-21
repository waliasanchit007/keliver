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
package dev.keliver.compose.extras

import dev.keliver.compose.gatea.compose.ExtraPressImpl
import dev.keliver.compose.internal.ModifierEventPairElement
import dev.keliver.compose.ui.Modifier

/**
 * The host feature of the press signal ([pressSignal]): a host that declares it renders the
 * modifier and sends its events. Check it (`dev.keliver.compose.LocalHostFeatures`) before sending
 * the modifier; keliver-nav's `prepareOnPress` does.
 */
public const val PRESS_HOST_FEATURE: String = "keliver.press@1"

/**
 * A Keliver extra, not Compose API: the host's press signal. [onPress] runs when a finger goes down
 * on this element, before its click (on a phone the finger stays down about 100 ms); [onCancel] when
 * that press ends without a click (the finger moved out, a scroll took the gesture). A press that
 * ends in a click calls neither again: the click follows.
 *
 * Send it only to a host that declares [PRESS_HOST_FEATURE] (D2). It does not replace the click:
 * keep `clickable` for the action, and use this to start work the click is likely to need
 * (keliver-nav's pre-composition of the screen the click opens).
 */
public fun Modifier.pressSignal(onPress: () -> Unit, onCancel: () -> Unit): Modifier =
  then(ModifierEventPairElement(onPress, onCancel) { press, cancel -> ExtraPressImpl(onPress = press, onCancel = cancel) })
