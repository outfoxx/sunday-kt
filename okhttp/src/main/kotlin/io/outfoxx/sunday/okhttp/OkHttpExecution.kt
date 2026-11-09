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

import io.outfoxx.sunday.unwrapRequestException
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.coroutines.executeAsync
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Executes native I/O with cancellation and body-failure preservation; policy belongs to the transport. */
internal suspend fun OkHttpClient.executeRequest(
  request: okhttp3.Request,
  isBodyRepeatable: Boolean = true,
): OkHttpResponse {
  // OkHttp's async contract requires IOException. Retain the original body failure per attempt.
  val failure = AtomicReference<Throwable?>()
  val wireRequest =
    request.body?.let { body ->
      request
        .newBuilder()
        .method(
          request.method,
          object : RequestBody() {
            override fun contentType() = body.contentType()

            override fun contentLength() = body.contentLength()

            // Native follow-ups must honor restrictions declared on the Sunday wrapper too.
            override fun isOneShot() = !isBodyRepeatable || body.isOneShot()

            override fun isDuplex() = body.isDuplex()

            override fun writeTo(sink: BufferedSink) {
              try {
                body.writeTo(sink)
              } catch (error: Exception) {
                failure.compareAndSet(null, error)
                throw IOException("Request body failed", error)
              }
            }
          },
        ).build()
    } ?: request
  val call = newCall(wireRequest)
  val response =
    try {
      call.executeAsync()
    } catch (error: IOException) {
      throw failure.get() ?: error.unwrapRequestException()
    }
  return OkHttpResponse(response, call::cancel)
}
