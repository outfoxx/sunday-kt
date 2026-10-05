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

import io.outfoxx.sunday.security.OAuthTokenProvider
import io.outfoxx.sunday.security.TokenProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** OAuth exchange using a dedicated OkHttp client without ambient auth, cookies, or redirects. */
class OkHttpOAuthTokenProvider(
  configuration: OAuthTokenProvider.Configuration,
  httpClient: OkHttpClient =
    OkHttpClient
      .Builder()
      .followRedirects(false)
      .followSslRedirects(false)
      .build(),
) : TokenProvider.Refreshing by OAuthTokenProvider(configuration, { request ->
    val builder = Request.Builder().url(request.uri.toURL())
    request.headers.forEach { (name, value) -> builder.header(name, value) }
    request.encodedForm()?.let {
      val body = it.toRequestBody("application/x-www-form-urlencoded".toMediaType())
      builder.post(
        object : RequestBody() {
          override fun contentType() = body.contentType()

          override fun contentLength() = body.contentLength()

          override fun isOneShot() = true

          override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        },
      )
    }
    suspendCancellableCoroutine { continuation ->
      // Authorization codes and rotating refresh tokens must never be replayed by HTTP recovery.
      val call =
        httpClient
          .newBuilder()
          .retryOnConnectionFailure(false)
          .build()
          .newCall(builder.build())
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : Callback {
          override fun onFailure(
            call: Call,
            e: IOException,
          ) {
            if (continuation.isActive) continuation.resumeWithException(e)
          }

          override fun onResponse(
            call: Call,
            response: Response,
          ) {
            response.use {
              if (continuation.isActive) {
                val result =
                  runCatching { OAuthTokenProvider.ExchangeResponse(response.code, response.body?.string().orEmpty()) }
                continuation.resumeWith(result)
              }
            }
          }
        },
      )
    }
  }) {
  init {
    require(
      !httpClient.followRedirects &&
        !httpClient.followSslRedirects &&
        httpClient.authenticator === Authenticator.NONE &&
        httpClient.cookieJar === CookieJar.NO_COOKIES &&
        httpClient.interceptors.isEmpty() &&
        httpClient.networkInterceptors.isEmpty(),
    ) {
      "OAuth HTTP client must disable redirects, ambient authentication, cookies, and interceptors"
    }
  }
}
