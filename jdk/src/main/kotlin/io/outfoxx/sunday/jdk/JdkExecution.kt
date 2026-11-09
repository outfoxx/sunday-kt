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

import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodySubscribers
import java.util.concurrent.atomic.AtomicReference

/** Executes native I/O with cancellation and body-failure preservation; policy belongs to the transport. */
internal suspend fun HttpClient.executeRequest(request: HttpRequest): JdkResponse {
  val stream = AtomicReference<InputStream>()
  val response =
    this
      .sendAsync(request) {
        BodySubscribers.mapping(BodySubscribers.ofInputStream()) { input ->
          stream.set(input)
          input.asSource().buffered()
        }
      }.requestAwait { it.body().close() }
  return JdkResponse(response) { stream.get()?.close() }
}
