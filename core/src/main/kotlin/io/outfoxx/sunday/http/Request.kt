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

package io.outfoxx.sunday.http

import kotlinx.coroutines.flow.Flow
import kotlinx.io.Buffer
import kotlinx.io.Source
import java.net.URI

/**
 * HTTP request based on coroutines.
 *
 * [Request] can produce a complete [response][Response] by calling [execute] or
 * can produce a [flow][Flow] of [events][Request.Event] by calling [start].
 */
interface Request {

  /**
   * HTTP method for request.
   */
  val method: Method

  /**
   * Target URL for request.
   */
  val uri: URI

  /**
   * Headers to send with request.
   */
  val headers: Headers

  /**
   * Body data to send with request.
   */
  suspend fun body(): Source?

  /**
   * Executes the request and returns a complete [response][Response].
   */
  suspend fun execute(): Response

  /**
   * Events representing the state of the HTTP request/response.
   */
  sealed interface Event {

    /**
     * HTTP response has started.
     *
     * The provided response will include status and headers. [Response.body]
     * and [Response.trailers] will not be available. The response body data,
     * if any, will be provided in [Data] events that follow.
     */
    data class Start(
      val value: Response,
    ) : Event

    /**
     * HTTP response body data has been received.
     */
    data class Data(
      val value: Buffer,
    ) : Event

    /**
     * HTTP response has completed.
     *
     * Response trailers are provided if any were delivered.
     */
    data class End(
      val trailers: Headers,
    ) : Event
  }

  /**
   * Starts the HTTP request and returns a [Flow] of [events][Request.Event].
   */
  fun start(): Flow<Event>

}
