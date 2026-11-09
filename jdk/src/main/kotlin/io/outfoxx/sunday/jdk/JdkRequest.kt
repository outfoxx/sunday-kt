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

import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import kotlinx.coroutines.jdk9.collect
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.write
import java.net.URI
import java.net.http.HttpRequest

/** JDK HTTP request. Native publishers and builders retain their platform replay semantics. */
open class JdkRequest(
  override val transport: HttpRequest,
) : Request<HttpRequest> {

  override val method: Method get() = Method.valueOf(transport.method())
  override val uri: URI get() = transport.uri()
  override val headers: Headers get() =
    transport.headers().map().flatMap { (name, values) ->
      values.map {
        name to
          it
      }
    }
  override val hasBody: Boolean get() = transport.bodyPublisher().map { it.contentLength() != 0L }.orElse(false)

  // JDK BodyPublisher requires each repeat subscription to reproduce the same data.
  override val isBodyRepeatable: Boolean get() = true

  /** Replaces the native request using a platform builder. */
  override fun copy(transport: HttpRequest): JdkRequest = JdkRequest(transport)

  override fun copy(
    method: Method,
    uri: URI,
    headers: Headers,
  ): JdkRequest =
    JdkRequest(
      transport
        .copyToBuilder(false)
        .uri(uri)
        .headers(headers)
        .method(method.name, transport.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
        .build(),
    )

  override fun withBody(body: StreamingBody?): JdkRequest =
    copy(
      transport
        .copyToBuilder()
        .method(
          method.name,
          body?.bodyPublisher() ?: HttpRequest.BodyPublishers.noBody(),
        ).build(),
    )

  override suspend fun body(): Source? {
    val publisher = transport.bodyPublisher().orElse(null) ?: return null
    return Buffer().also { buffer -> publisher.collect { buffer.write(it) } }
  }


}

/** Uses a fresh stream for each JDK publisher subscription, preserving factory failures as native causes. */
internal fun StreamingBody.bodyPublisher(): HttpRequest.BodyPublisher {
  val publisher = HttpRequest.BodyPublishers.ofInputStream { openSource().asInputStream() }
  return when (val length = contentLength) {
    null -> publisher
    0L -> HttpRequest.BodyPublishers.noBody()
    else -> HttpRequest.BodyPublishers.fromPublisher(publisher, length)
  }
}
