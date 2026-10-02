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

import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.test.EventSourceTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import java.time.Duration

class OkHttpEventSourceTest : EventSourceTest() {

  class OkHttpTrackingRequest(
    request: okhttp3.Request,
    httpClient: OkHttpClient,
    requestDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onStart: () -> Unit,
  ) : OkHttpRequest(
      request,
      httpClient,
      requestDispatcher,
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
    OkHttpTrackingRequest(
      okhttp3.Request
        .Builder()
        .method("GET", null)
        .url(url)
        .headers(headers.toMap().toHeaders())
        .build(),
      // EventSource owns timeout behavior; a native read timeout must not hide failed cancellation.
      OkHttpClient.Builder().readTimeout(Duration.ZERO).build(),
      onStart = onStart,
    )

}
