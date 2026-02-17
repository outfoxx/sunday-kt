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
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import kotlinx.io.Source
import java.net.http.HttpClient
import java.net.http.HttpResponse

/**
 * JDK11 HTTP Client implementation of [Response] based on [HttpResponse].
 */
class JdkResponse(
  private val response: HttpResponse<Source>,
  private val httpClient: HttpClient,
) : Response {

  override val statusCode: Int
    get() = response.statusCode()

  override val reasonPhrase: String? by lazy {
    ReasonPhrases.lookup(response.statusCode())
  }

  override val headers: Headers by lazy {
    response.headers().map().flatMap { entry -> entry.value.map { entry.key to it } }
  }

  override val body: Source?
    get() = response.body()

  override val trailers: Headers?
    get() = null

  override val request: Request
    get() = JdkRequest(response.request(), httpClient)

}
