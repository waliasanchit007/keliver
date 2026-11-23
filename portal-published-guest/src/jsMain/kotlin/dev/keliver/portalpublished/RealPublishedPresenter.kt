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
package dev.keliver.portalpublished

import androidx.compose.runtime.Composable
import dev.keliver.material.protocol.guest.KeliverMaterialProtocolWidgetSystemFactory
import dev.keliver.portaldevice.PortalPresenter
import dev.keliver.treehouse.StandardAppLifecycle
import dev.keliver.treehouse.TreehouseUi
import dev.keliver.treehouse.ZiplineTreehouseUi
import dev.keliver.treehouse.asZiplineTreehouseUi
import kotlinx.serialization.json.Json

/**
 * The published presenter: renders the COMPILED screen (no relay, no
 * interpreter). Same service surface as the dev presenter, so the host app
 * only switches the manifest URL + signature verifier.
 */
class RealPublishedPresenter(
  json: Json,
  private val sql: dev.keliver.portal.sql.HostSqlDriver?, // M7 capability
) : PortalPresenter {
  override val appLifecycle = StandardAppLifecycle(
    protocolWidgetSystemFactory = KeliverMaterialProtocolWidgetSystemFactory,
    json = json,
    widgetVersion = 1U,
  )

  override fun launch(): ZiplineTreehouseUi =
    PublishedTreehouseUi(sql).asZiplineTreehouseUi(appLifecycle)
}

private class PublishedTreehouseUi(
  private val sql: dev.keliver.portal.sql.HostSqlDriver?,
) : TreehouseUi {
  @Composable
  override fun Show() {
    PublishedEntry(sql)
  }
}
