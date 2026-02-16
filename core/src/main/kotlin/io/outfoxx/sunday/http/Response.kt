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

import io.outfoxx.sunday.MediaType
import kotlinx.io.Source

/**
 * HTTP response.
 */
interface Response {

  /**
   * HTTP status code of the response.
   */
  val statusCode: Int

  /**
   * HTTP status message of the response.
   *
   * Some implementations (e.g., JDK HTTP Client) do not support reporting
   * the reason phrase. When not supported a standard phrase will be provided
   * unless there is no standard phrase, in which case `null` will be returned.
   */
  val reasonPhrase: String?

  /**
   * HTTP response headers, if any, were delivered.
   */
  val headers: Headers

  /**
   * HTTP response data source, if available.
   *
   * [Response.body] will be null if the body data is not yet
   * available; as when the [response][Response] is provided by
   * a [Request.Event.Start] event.
   */
  val body: Source?

  /**
   * HTTP response trailers, if any, were delivered.
   *
   * [Response.trailers] will be null if the trailers are not yet
   * available. Trailers won't be available until the [Response.body]
   * has been consumed and closed or when the [response][Response] is provided by
   * a [Request.Event.Start] event.
   */
  val trailers: Headers?

  /**
   * HTTP request after API rewrites and/or HTTP redirects.
   */
  val request: Request

}

/**
 * Convenience for checking if the [Response.statusCode]
 * represents a successful response.
 */
val Response.isSuccessful: Boolean
  get() = statusCode in 200..299


/**
 * Convenience accessor for HTTP Content-Length header.
 */
val Response.contentLength: Long?
  get() = headers.getFirstOrNull(HeaderNames.CONTENT_LENGTH)?.toLong()


/**
 * Convenience accessor for HTTP Content-Type header.
 */
val Response.contentType: MediaType?
  get() = headers.getFirstOrNull(HeaderNames.CONTENT_TYPE)?.let(MediaType::from)
