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

package io.outfoxx.sunday

import io.outfoxx.sunday.http.NonRepeatableRequest
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.ResponseEvent
import io.outfoxx.sunday.http.copy
import io.outfoxx.sunday.security.withSecurityContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import java.io.IOException
import java.net.ProtocolException

/** Transport-owned adapter configuration. Invocation state is local to each execution. */
class RequestExecution<TReq, TRes>(
  requestAdapter: RequestAdapter? = null,
  responseAdapter: ResponseAdapter? = null,
  private val nativeRequestAdapter: (suspend (Request<TReq>) -> Request<TReq>)? = null,
) {
  private val requestAdapter = RequestAdapters(listOfNotNull(requestAdapter))
  private val responseAdapter = ResponseAdapters(listOfNotNull(responseAdapter))

  /** Adapts once for request export; the consumer owns subsequent execution. */
  suspend fun export(request: Request<TReq>): Request<TReq> = adapt(request).let { it.withSecurityContext(null) }

  private suspend fun adapt(request: Request<TReq>): Request<TReq> {
    val nativeAdapted = nativeRequestAdapter?.let { request.adaptedBy(it) } ?: request
    return requestAdapter.adapt(nativeAdapted)
  }

  private suspend fun <T> execute(
    request: Request<TReq>,
    consume: suspend (Response<TRes>) -> T,
    send: suspend (Request<TReq>) -> Response<TRes>,
    redirect: (Request<TReq>, Response<TRes>, Request<*>) -> Request<TReq>?,
  ): T {
    var prepared = request
    var redirects = 0
    while (true) {
      val sent = adapt(prepared)
      currentCoroutineContext().ensureActive()
      val original = send(sent)
      var response = original
      AutoCloseable { response.body?.close() }.use {
        // Native interceptors may change credentials after request adaptation. Keep the receipts,
        // but compare them against the request that actually reached the server.
        val native = original.request.withSecurityContext(sent.securityContext)
        val effective =
          if (sent.isBodyRepeatable || !native.isBodyRepeatable) {
            native
          } else {
            // Native reconstruction cannot relax an adapter's restriction on replaying its body.
            NonRepeatableRequest(native)
          }
        response = responseAdapter.adapt(original, effective)
        val result =
          response.cancellable {
            currentCoroutineContext().ensureActive()
            val next = redirect(prepared, response, effective)
            if (next != null) {
              if (++redirects > 20) throw ProtocolException("Too many redirects")
              Attempt.Retry(next)
            } else {
              Attempt.Complete(consume(response))
            }
          }
        when (result) {
          is Attempt.Retry -> prepared = result.request
          is Attempt.Complete -> return result.value
        }
      }
    }
  }

  /** Adapts headers before collecting the accepted body in bulk on the I/O dispatcher. */
  suspend fun execute(
    request: Request<TReq>,
    send: suspend (Request<TReq>) -> Response<TRes>,
    redirect: (Request<TReq>, Response<TRes>, Request<*>) -> Request<TReq>? = { _, _, _ -> null },
  ): Response<TRes> =
    execute(request, { response ->
      withContext(Dispatchers.IO) {
        val buffer = response.body?.let { Buffer().also { target -> it.transferTo(target) } }
        response.copy(body = buffer)
      }
    }, send, redirect)

  /** Adapts each event connection before exposing its start event or reading its body. */
  fun start(
    request: Request<TReq>,
    send: suspend (Request<TReq>) -> Response<TRes>,
    redirect: (Request<TReq>, Response<TRes>, Request<*>) -> Request<TReq>? = { _, _, _ -> null },
  ): Flow<ResponseEvent<TRes>> =
    flow {
      execute(request, { emitResponse(it) }, send, redirect)
    }.flowOn(Dispatchers.IO)

  private sealed interface Attempt<out TReq, out T> {
    data class Retry<TReq>(
      val request: Request<TReq>,
    ) : Attempt<TReq, Nothing>

    data class Complete<T>(
      val value: T,
    ) : Attempt<Nothing, T>
  }
}

private suspend fun <TRes> kotlinx.coroutines.flow.FlowCollector<ResponseEvent<TRes>>.emitResponse(
  response: Response<TRes>,
) {
  emit(ResponseEvent.Start(response.copy(body = null)))
  val source = response.body
  if (source != null) {
    while (true) {
      currentCoroutineContext().ensureActive()
      val buffer = Buffer()
      if (source.readAtMostTo(buffer, 8192) == -1L) break
      emit(ResponseEvent.Data(buffer))
    }
  }
  emit(ResponseEvent.End(response.trailers ?: emptyList()))
}

// Keep acquired replacements in the caller's ownership before this scope can report cancellation.
internal suspend fun <T> Response<*>.cancellable(block: suspend () -> T): T =
  coroutineScope {
    val finished = CompletableDeferred<Unit>()
    // Cancellation must not queue behind blocked readers. This observer only invokes the native,
    // thread-safe cancel operation; the reader retains ownership of the buffered source.
    // Start immediately even inside an unconfined event loop, before an adapter can block.
    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
      try {
        finished.await()
      } catch (cancelled: CancellationException) {
        runCatching { cancel() }
        throw cancelled
      }
    }
    try {
      currentCoroutineContext().ensureActive()
      block()
    } catch (error: IOException) {
      // Native cancellation commonly interrupts a read with IOException. Preserve deliberate stop
      // signals, but report cancellation of the caller's job using coroutine semantics.
      if (!error.isRequestCancelled) currentCoroutineContext().ensureActive()
      throw error
    } finally {
      finished.complete(Unit)
    }
  }
