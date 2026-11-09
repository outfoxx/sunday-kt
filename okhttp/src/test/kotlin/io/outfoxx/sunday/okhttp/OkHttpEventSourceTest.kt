/*
 * Copyright 2020 Outfox, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.outfoxx.sunday.okhttp

import io.outfoxx.sunday.RequestExecution
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.security.AuthenticationRecoveryBudget
import io.outfoxx.sunday.test.EventSourceTest
import org.junit.jupiter.api.AfterEach

class OkHttpEventSourceTest : EventSourceTest<okhttp3.Request, okhttp3.Response>() {
  private val client = okhttp3.OkHttpClient().reconfiguredForEvents()

  override fun createRequest(
    url: String,
    headers: Headers,
  ): Request<okhttp3.Request> =
    OkHttpRequest(
      okhttp3.Request
        .Builder()
        .url(url)
        .headers(
          okhttp3.Headers
            .Builder()
            .apply {
              headers.forEach { (name, value) -> add(name, value) }
            }.build(),
        ).build(),
    )

  override fun startRequest(request: Request<okhttp3.Request>) =
    RequestExecution<okhttp3.Request, okhttp3.Response>().start(
      request,
      AuthenticationRecoveryBudget(),
      send = { client.executeRequest(it.transport) },
    )

  @AfterEach
  fun closeClient() {
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
  }
}
