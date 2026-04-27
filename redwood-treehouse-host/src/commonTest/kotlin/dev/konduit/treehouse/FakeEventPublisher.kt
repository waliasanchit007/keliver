/*
 * Copyright (C) 2023 Square, Inc.
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
package dev.konduit.treehouse

import dev.konduit.protocol.EventTag
import dev.konduit.protocol.Id
import dev.konduit.protocol.WidgetTag
import dev.konduit.protocol.host.ProtocolMismatchHandler
import app.cash.zipline.EventListener

class FakeEventPublisher : EventPublisher {
  override val ziplineEventListener = EventListener.NONE

  override val widgetProtocolMismatchHandler = ProtocolMismatchHandler.Throwing

  override fun onUnknownEvent(widgetTag: WidgetTag, tag: EventTag) {
  }

  override fun onUnknownEventNode(id: Id, tag: EventTag) {
  }

  override fun onUncaughtException(exception: Throwable) {
  }

  override fun close() {
  }
}
