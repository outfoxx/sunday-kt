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
import io.outfoxx.sunday.http.Response
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.okio.asKotlinxIoRawSource

/**
 * Okhttp implementation of [Response].
 */
class OkHttpResponse(
  val nativeResponse: okhttp3.Response,
  private val cancelCall: () -> Unit = {},
) : Response<okhttp3.Response> {

  override val transport: okhttp3.Response get() = nativeResponse

  override fun cancel() = cancelCall()

  override val statusCode: Int
    get() = nativeResponse.code

  override val reasonPhrase: String
    get() = nativeResponse.message

  override val headers: Headers
    get() = nativeResponse.headers

  override val body: Source? by lazy {
    nativeResponse.body
      .source()
      .asKotlinxIoRawSource()
      .buffered()
  }

  override val trailers: Headers?
    get() = nativeResponse.peekTrailers()

  override val request: OkHttpRequest by lazy {
    // The outer response describes the application request; network metadata includes later
    // interceptor rewrites and the final transmission when native recovery retried the call.
    OkHttpRequest(nativeResponse.networkResponse?.request ?: nativeResponse.request)
  }

}
