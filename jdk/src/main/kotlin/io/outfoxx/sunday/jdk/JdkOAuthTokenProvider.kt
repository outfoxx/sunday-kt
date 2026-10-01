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

import io.outfoxx.sunday.security.OAuthTokenProvider
import io.outfoxx.sunday.security.TokenProvider
import kotlinx.coroutines.future.await
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** OAuth exchange using a dedicated JDK client without ambient auth, cookies, or redirects. */
class JdkOAuthTokenProvider(
  configuration: OAuthTokenProvider.Configuration,
  httpClient: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
) : TokenProvider.Refreshing by OAuthTokenProvider(configuration, { request ->
    val builder = HttpRequest.newBuilder(request.uri)
    request.headers.forEach { (name, value) -> builder.header(name, value) }
    request.encodedForm()?.let { builder.POST(HttpRequest.BodyPublishers.ofString(it)) }
    val response = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
    OAuthTokenProvider.ExchangeResponse(response.statusCode(), response.body())
  }) {
  init {
    require(
      httpClient.followRedirects() == HttpClient.Redirect.NEVER &&
        httpClient.authenticator().isEmpty &&
        httpClient.cookieHandler().isEmpty,
    ) {
      "OAuth HTTP client must disable redirects, ambient authentication, and cookies"
    }
  }
}
