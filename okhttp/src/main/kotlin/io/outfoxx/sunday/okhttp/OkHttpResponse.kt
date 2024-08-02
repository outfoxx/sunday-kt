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
import okhttp3.OkHttpClient
import okio.BufferedSource

/**
 * Okhttp implementation of [Response].
 */
class OkHttpResponse(
  private val response: okhttp3.Response,
  private val httpClient: OkHttpClient,
) : Response {

  override val statusCode: Int
    get() = response.code

  override val reasonPhrase: String
    get() = response.message

  override val headers: Headers
    get() = response.headers

  override val body: BufferedSource?
    get() = response.body?.source()

  override val trailers: Headers?
    get() =
      try {
        response.trailers()
      } catch (ignored: IllegalStateException) {
        null
      }

  override val request: OkHttpRequest by lazy {
    OkHttpRequest(response.request, httpClient)
  }

}
