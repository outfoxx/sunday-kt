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
import io.outfoxx.sunday.test.EventSourceTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest

class JdkEventSourceTest : EventSourceTest() {

  class JdkTrackingRequest(
    request: HttpRequest,
    httpClient: HttpClient,
    private val onStart: () -> Unit,
  ) : JdkRequest(
      request,
      httpClient,
    ) {

    override fun start(): Flow<Request.Event> =
      super
        .start()
        .onEach {
          if (it is Request.Event.Start) {
            onStart()
          }
        }
  }

  override fun createRequest(
    url: String,
    headers: Headers,
    onStart: () -> Unit,
  ): Request =
    JdkTrackingRequest(
      HttpRequest
        .newBuilder(URI(url))
        .headers(headers)
        .build(),
      HttpClient.newHttpClient(),
      onStart,
    )

}
