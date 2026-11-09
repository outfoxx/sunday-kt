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


import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.security.SecurityContext
import kotlinx.io.Source
import java.net.URI

/** HTTP request representation wrapping the native request type [TReq]. */
interface Request<TReq> {

  /** Native request represented by this wrapper. */
  val transport: TReq

  /** Credential metadata retained when copying native requests; never contains execution state. */
  val securityContext: SecurityContext? get() = null

  /** True unless the body is known to contain no bytes, without consuming it. */
  val hasBody: Boolean get() = true

  /**
   * Whether the body can be produced again, independently of length and operation retry policy.
   * Unknown implementations are conservatively non-repeatable; native requests also return true for no body.
   */
  val isBodyRepeatable: Boolean get() = false

  /** Copies wire fields while preserving native settings and the existing body resource. */
  fun copy(
    method: Method = this.method,
    uri: URI = this.uri,
    headers: Headers = this.headers,
  ): Request<TReq> = throw UnsupportedOperationException("This request does not support copying")

  /** Replaces the native request while preserving wrapper metadata. */
  fun copy(transport: TReq): Request<TReq> =
    throw UnsupportedOperationException("This request does not support native copying")

  /** Replaces the body with a repeatable source factory, or removes it when null. */
  fun withBody(body: StreamingBody?): Request<TReq> =
    throw UnsupportedOperationException("This request does not support bodies")

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

}
