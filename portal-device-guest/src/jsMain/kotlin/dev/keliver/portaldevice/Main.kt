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
package dev.keliver.portaldevice

import app.cash.zipline.Zipline
import dev.keliver.portal.sql.HostSqlDriver

/**
 * Zipline guest entry — runs once when the host loads the bundle. The host has
 * already bound its services (bindServices), so we take them and bind the guest
 * presenter under "PortalPresenter" for the host to take().
 */
fun main() {
  val zipline = Zipline.get()
  val hostApi = zipline.take<HostApi>("HostApi")
  // M9: the compiled screen's data layer (bound by hosts declaring HostSqlDriver@1).
  val sql = runCatching { zipline.take<HostSqlDriver>("HostSqlDriver") }.getOrNull()
  zipline.bind<PortalPresenter>("PortalPresenter", RealPortalPresenter(hostApi, sql, zipline.json))
}
