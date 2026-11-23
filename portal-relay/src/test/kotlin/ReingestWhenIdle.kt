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
 * [DocumentService.reingest] answers false while a write is still in flight
 * (its status can already be set while the write's task finishes), and the
 * relay retries (Relay.forceReingest). Tests retry the same way instead of
 * asserting the first answer.
 */
fun reingestWhenIdle(svc: DocumentService, timeoutMillis: Long = 5_000): Boolean {
  val deadline = System.currentTimeMillis() + timeoutMillis
  while (!svc.reingest()) {
    if (System.currentTimeMillis() > deadline) return false
    Thread.sleep(25)
  }
  return true
}
