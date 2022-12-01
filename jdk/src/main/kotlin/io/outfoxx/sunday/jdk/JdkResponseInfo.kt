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
import io.outfoxx.sunday.http.Response
import okio.BufferedSource
import java.net.http.HttpResponse.ResponseInfo

/**
 * JDK11 HTTP Client implementation of [Response] based on [ResponseInfo].
 */
class JdkResponseInfo(
  private val responseInfo: ResponseInfo,
  override val request: JdkRequest,
) : Response {

  override val statusCode: Int
    get() = responseInfo.statusCode()

  override val reasonPhrase: String? =
    null

  override val headers: Headers by lazy {
    responseInfo.headers().map().flatMap { entry -> entry.value.map { entry.key to it } }
  }

  override val body: BufferedSource?
    get() = null

  override val trailers: Headers?
    get() = null

}
