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

import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource
import kotlinx.io.okio.asOkioSource
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.URI

/** OkHttp request. Builders retain native tags, body replay capabilities, and all request settings. */
open class OkHttpRequest(
  override val transport: okhttp3.Request,
) : Request<okhttp3.Request> {


  override val method: Method get() = Method.valueOf(transport.method)
  override val uri: URI get() = transport.url.toUri()
  override val headers: Headers get() = transport.headers
  override val hasBody: Boolean get() = transport.body?.contentLength()?.let { it != 0L } ?: false
  override val isBodyRepeatable: Boolean get() =
    transport.body?.let { !it.isOneShot() && !it.isDuplex() } ?: true

  /** Replaces the native request using a platform builder. */
  override fun copy(transport: okhttp3.Request): OkHttpRequest = OkHttpRequest(transport)

  override fun copy(
    method: Method,
    uri: URI,
    headers: Headers,
  ): OkHttpRequest {
    val builder =
      transport
        .newBuilder()
        .url(uri.toURL())
        .headers(
          okhttp3.Headers
            .Builder()
            .apply { headers.forEach { (name, value) -> add(name, value) } }
            .build(),
        ).method(
          method.name,
          when {
            method == this.method -> transport.body
            method.bodyAllowed -> transport.body ?: RequestBody.EMPTY
            else -> null
          },
        )
    return OkHttpRequest(builder.build())
  }

  override fun withBody(body: StreamingBody?): OkHttpRequest =
    copy(
      transport
        .newBuilder()
        .method(
          transport.method,
          body?.let { StreamingRequestBody(it, transport.body?.contentType()) }
            ?: if (method.bodyAllowed) RequestBody.EMPTY else null,
        ).build(),
    )

  override suspend fun body(): Source? =
    transport.body?.let { body ->
      okio
        .Buffer()
        .also { body.writeTo(it) }
        .asKotlinxIoRawSource()
        .buffered()
    }


}

/** Bridges sources in bulk through Okio; each write opens a fresh body source. */
internal class StreamingRequestBody(
  private val body: StreamingBody,
  private val type: okhttp3.MediaType?,
) : RequestBody() {
  override fun contentType() = type

  override fun contentLength(): Long = body.contentLength ?: -1L

  override fun writeTo(sink: BufferedSink) {
    body.openSource().use { sink.writeAll(it.asOkioSource()) }
  }
}
