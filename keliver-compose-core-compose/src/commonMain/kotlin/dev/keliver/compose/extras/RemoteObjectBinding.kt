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

import androidx.compose.runtime.Composable
import dev.keliver.compose.core.compose.ExtraRemoteObject
import dev.keliver.compose.remote.RemoteObject

/**
 * A Keliver extra, not Compose API (MS4-43, G2): binds a remote object (the guest's proxy of a
 * host-owned state class, such as the `PagerState` that `rememberPagerState` returns) to the host.
 * The proxy's wire is the widget's property, and the host's report comes back as its event, which
 * reaches the proxy's fields and commands. A widget that takes a state does this itself; this is the
 * same binding for a state a screen holds without a widget (and for the tests).
 */
@Composable
public fun RemoteObjectBinding(remote: RemoteObject) {
  ExtraRemoteObject(wire = remote.wire, onReport = { remote.onHostReport(it) })
}
