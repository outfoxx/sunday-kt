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
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.internal.connection.RealCall
import okio.Buffer
import okio.BufferedSource
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Okhttp implementation of [Request]
 */
class OkHttpRequest(
  private val request: okhttp3.Request,
  private val httpClient: OkHttpClient,
  private val requestDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Request {

  companion object {

    private val logger = LoggerFactory.getLogger(OkHttpRequest::class.java)
    private const val EOF = -1L
    private const val READ_SIZE = 8192L
  }

  override val method: Method by lazy {
    Method.valueOf(request.method)
  }

  override val uri: URI by lazy {
    request.url.toUri()
  }

  override val headers: Headers
    get() = request.headers

  override suspend fun body(): BufferedSource? =
    request.body?.let { requestBody ->
      val buffer = Buffer()
      requestBody.writeTo(buffer)
      buffer
    }

  override suspend fun execute(): OkHttpResponse {
    logger.debug("Executing")

    val call = httpClient.newCall(request)

    return suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation {
        call.cancel()
      }
      call.enqueue(
        object : Callback {
          override fun onResponse(call: Call, response: okhttp3.Response) {

            logger.debug("Received response")

            // Don't bother with resuming the continuation if it is already cancelled.
            if (continuation.isCancelled) return

            continuation.resume(OkHttpResponse(response, httpClient))
          }

          override fun onFailure(call: Call, e: IOException) {

            logger.debug("Received error")

            // Don't bother with resuming the continuation if it is already cancelled.
            if (continuation.isCancelled) return

            continuation.resumeWithException(e)
          }
        }
      )
    }
  }

  override fun start(): Flow<Request.Event> {
    return callbackFlow {

      logger.debug("Starting")

      val callback = RequestCallback(this, httpClient, requestDispatcher)

      val call = httpClient.newCall(request)
      call.enqueue(callback)

      awaitClose {
        call.cancel()
        callback.cancel()
      }
    }
  }

  class RequestCallback(
    private val scope: ProducerScope<Request.Event>,
    private val httpClient: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
  ) : Callback {

    private var reader: Job? = null

    fun cancel() {
      reader?.cancel()
    }

    override fun onResponse(call: Call, response: okhttp3.Response) {

      logger.debug("Received response")

      reader = scope.launch(dispatcher) {

        response.use {

          // Because they called `start`, this is a long-lived response,
          // cancel full-call timeouts.
          (call as? RealCall)?.timeoutEarlyExit()

          val startEvent = Request.Event.Start(OkHttpResponse(response, httpClient))

          scope.send(startEvent)

          val body = response.body
          if (body != null) {

            logger.debug("Processing: response body")

            while (isActive && scope.isActive) {

              val buffer = Buffer()

              val bytesRead = body.source().read(buffer, READ_SIZE)
              if (bytesRead == EOF) {
                break
              }

              scope.send(Request.Event.Data(buffer))
            }

          }

          if (isActive && scope.isActive) {

            scope.send(Request.Event.End(response.trailers()))
          }
        }

        scope.close()

        logger.debug("Closed: response events")
      }
    }

    override fun onFailure(call: Call, e: IOException) {

      logger.debug("Received error")

      scope.cancel("Call failed", e)
    }
  }
}
