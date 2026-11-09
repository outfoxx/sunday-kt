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

package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.responseEvents
import io.outfoxx.sunday.test.EventSourceTest
import org.junit.jupiter.api.AfterEach

class JdkEventSourceTest : EventSourceTest<java.net.http.HttpRequest, java.net.http.HttpResponse<kotlinx.io.Source>>() {
  private val client =
    java.net.http.HttpClient
      .newHttpClient()

  override fun createRequest(
    url: String,
    headers: Headers,
  ): Request<java.net.http.HttpRequest> =
    JdkRequest(
      java.net.http.HttpRequest
        .newBuilder(java.net.URI(url))
        .headers(headers)
        .build(),
    )

  override fun startRequest(request: Request<java.net.http.HttpRequest>) =
    responseEvents {
      client.executeRequest(request.transport)
    }

  @AfterEach
  fun closeClient() {
    client.close()
  }
}
