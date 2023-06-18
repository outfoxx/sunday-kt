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
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.jdk9.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import okio.BufferedSource
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpResponse.BodySubscriber
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow.Subscription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JDK11 HTTP Client implementation of [Request].
 */
class JdkRequest(
  private val request: HttpRequest,
  private val httpClient: HttpClient,
) : Request {

  companion object {

    private val logger = LoggerFactory.getLogger(JdkRequest::class.java)
  }

  override val method: Method by lazy {
    Method.valueOf(request.method())
  }

  override val uri: URI by lazy {
    request.uri()
  }

  override val headers: Headers by lazy {
    request.headers().map().flatMap { entry -> entry.value.map { entry.key to it } }
  }

  override suspend fun body(): BufferedSource? {
    val bodyPublisher = request.bodyPublisher().orElse(null) ?: return null
    val requestBody = Buffer()
    bodyPublisher.collect { requestBody.write(it) }
    return requestBody
  }

  override suspend fun execute(): Response {
    logger.debug("Executing")

    val handler = BufferedSourceBodyHandler()

    val response =
      suspendCancellableCoroutine { continuation ->
        httpClient.sendAsync(request, handler)
          .whenComplete { response, error ->
            if (error != null) {
              continuation.resumeWithException(error)
            } else {
              continuation.resume(response)
            }
          }

        continuation.invokeOnCancellation { handler.cancel() }
      }

    return JdkResponse(response, httpClient)
  }

  override fun start(): Flow<Request.Event> {
    return callbackFlow {

      logger.debug("Starting")

      val handler = RequestEventBodyHandler(JdkRequest(request, httpClient), channel)

      val future = httpClient.sendAsync(request, handler)

      awaitClose {
        logger.debug("Canceling request")

        handler.cancel()
        future.cancel(true)
      }
    }
  }

  class BufferedSourceBodyHandler : BodyHandler<BufferedSource> {

    class Subscriber : BodySubscriber<BufferedSource> {

      private val buffer = Buffer()
      private var bodyFuture = CompletableFuture<BufferedSource>()
      private var subscription: Subscription? = null

      fun cancel() {
        subscription?.cancel()
      }

      override fun onSubscribe(subscription: Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
      }

      override fun onNext(item: MutableList<ByteBuffer>) {
        item.forEach { buffer.write(it) }
      }

      override fun onError(throwable: Throwable) {
        bodyFuture.completeExceptionally(throwable)
      }

      override fun onComplete() {
        bodyFuture.complete(buffer)
      }

      override fun getBody(): CompletionStage<BufferedSource> {
        return bodyFuture
      }

    }

    private var subscriber: Subscriber? = null

    fun cancel() {
      subscriber?.cancel()
    }

    override fun apply(responseInfo: HttpResponse.ResponseInfo?): BodySubscriber<BufferedSource> {
      subscriber = Subscriber()
      return subscriber!!
    }

  }

  class RequestEventBodyHandler(
    private val originalRequest: JdkRequest,
    private val channel: SendChannel<Request.Event>,
  ) : BodyHandler<Unit> {

    companion object {

      private val logger = LoggerFactory.getLogger(RequestEventBodyHandler::class.java)
    }

    class Subscriber(
      private val channel: SendChannel<Request.Event>,
    ) : BodySubscriber<Unit> {

      private val bodyFuture = CompletableFuture<Unit>()
      private var subscription: Subscription? = null

      fun cancel() {
        subscription?.cancel()
      }

      override fun onSubscribe(subscription: Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
      }

      override fun onNext(item: MutableList<ByteBuffer>) {
        val buffer = Buffer()
        item.forEach { buffer.write(it) }

        val dataEvent = Request.Event.Data(buffer)

        runBlocking { channel.send(dataEvent) }
      }

      override fun onError(throwable: Throwable) {
        bodyFuture.completeExceptionally(throwable)
      }

      override fun onComplete() {

        val endEvent = Request.Event.End(emptyList())

        channel.trySend(endEvent)
          .onSuccess { logger.trace("Sent: end") }
          .onFailure { logger.error("Failed to send: end") }

        bodyFuture.complete(Unit)
      }

      override fun getBody(): CompletionStage<Unit> {
        return bodyFuture
      }

    }

    private var subscriber: Subscriber? = null

    fun cancel() {
      subscriber?.cancel()
    }

    override fun apply(responseInfo: HttpResponse.ResponseInfo): BodySubscriber<Unit> {

      val startEvent =
        Request.Event.Start(
          JdkResponseInfo(
            responseInfo,
            originalRequest,
          )
        )

      channel.trySend(startEvent)
        .onSuccess { logger.trace("Sent: start") }
        .onFailure { logger.error("Failed to send: start") }

      subscriber = Subscriber(channel)
      return subscriber!!
    }

  }

}
