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
import io.outfoxx.sunday.security.AuthenticationRecoveryBudget
import io.outfoxx.sunday.security.RequestSecurity
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.TokenManager
import io.outfoxx.sunday.security.TokenProviderException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.jdk9.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.write
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
open class JdkRequest(
  private val request: HttpRequest,
  private val httpClient: HttpClient,
  private val security: RequestSecurity? = null,
  private val authorization: RequestSecurity.Credentials? = null,
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

  override suspend fun body(): Source? {
    val bodyPublisher = request.bodyPublisher().orElse(null) ?: return null
    val requestBody = Buffer()
    bodyPublisher.collect { requestBody.write(it) }
    return requestBody
  }

  /** Attaches selected credentials while refusing native redirect/authentication replay. */
  internal suspend fun authenticated(
    bindings: List<SecurityBinding>,
    manager: TokenManager?,
  ): JdkRequest {
    if (manager == null && bindings.isNotEmpty()) throw TokenProviderException()
    require(
      httpClient.followRedirects() == HttpClient.Redirect.NEVER &&
        httpClient.authenticator().isEmpty &&
        httpClient.cookieHandler().isEmpty,
    ) {
      "Managed security requires a JDK client without redirects, ambient authentication, or cookies"
    }
    val security = RequestSecurity(bindings, manager)
    val credentials = security.authorize(uri, headers)
    return JdkRequest(request.withCredentials(credentials), httpClient, security, credentials)
  }

  override suspend fun execute(): Response {
    val security = security ?: return executeNative(request)
    var credentials = security.authorize(uri, headers, authorization)
    var response = executeNative(request.withCredentials(credentials))
    if (security.recover(
        method,
        request
          .bodyPublisher()
          .map {
            it.contentLength() != 0L
          }.orElse(false),
        credentials,
        response,
      )
    ) {
      response.body?.close()
      credentials = security.authorize(uri, headers, authorization)
      response = executeNative(request.withCredentials(credentials))
    }
    return response.redacted(security)
  }

  private fun Response.redacted(security: RequestSecurity): Response {
    val source = request as JdkRequest
    val credentials = security.redact(source.uri, source.headers)
    return object : Response by this {
      override val request = JdkRequest(source.request.withCredentials(credentials), httpClient)
    }
  }

  private suspend fun executeNative(request: HttpRequest): Response {
    logger.debug("Executing")

    val handler = BufferedSourceBodyHandler()

    val response =
      suspendCancellableCoroutine { continuation ->
        val future = httpClient.sendAsync(request, handler)
        future.whenComplete { response, error ->
          if (!continuation.isActive) {
            response?.body()?.close()
          } else if (error != null) {
            continuation.resumeWithException(error)
          } else {
            continuation.resume(response) { _, value, _ -> value.body().close() }
          }
        }
        continuation.invokeOnCancellation {
          handler.cancel()
          future.cancel(true)
        }
      }

    return JdkResponse(response, httpClient)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun start(): Flow<Request.Event> =
    flow {
      val recoveryBudget = currentCoroutineContext()[AuthenticationRecoveryBudget] ?: AuthenticationRecoveryBudget()
      val security = security
      if (security == null) {
        startNative(request).collect { emit(it) }
        return@flow
      }
      do {
        var replay = false
        val credentials = security.authorize(uri, headers, authorization)
        startNative(request.withCredentials(credentials))
          .transformWhile { event ->
            if (event is Request.Event.Start &&
              security.recover(
                method,
                request.bodyPublisher().map { it.contentLength() != 0L }.orElse(false),
                credentials,
                event.value,
                recoveryBudget,
              )
            ) {
              replay = true
              false
            } else {
              emit(if (event is Request.Event.Start) Request.Event.Start(event.value.redacted(security)) else event)
              true
            }
          }.collect { emit(it) }
      } while (replay)
    }

  private fun startNative(request: HttpRequest): Flow<Request.Event> =
    callbackFlow {
      logger.debug("Starting")

      val handler = RequestEventBodyHandler(JdkRequest(request, httpClient), channel)

      val future = httpClient.sendAsync(request, handler)
      future.whenComplete { _, error -> channel.close(error) }

      awaitClose {
        logger.debug("Canceling request")

        handler.cancel()
        future.cancel(true)
      }
    }

  class BufferedSourceBodyHandler : BodyHandler<Source> {

    class Subscriber : BodySubscriber<Source> {

      private val buffer = Buffer()
      private var bodyFuture = CompletableFuture<Source>()
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

      override fun getBody(): CompletionStage<Source> = bodyFuture

    }

    private var subscriber: Subscriber? = null

    fun cancel() {
      subscriber?.cancel()
    }

    override fun apply(responseInfo: HttpResponse.ResponseInfo?): BodySubscriber<Source> {
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

        channel
          .trySend(endEvent)
          .onSuccess { logger.trace("Sent: end") }
          .onFailure { logger.error("Failed to send: end") }

        bodyFuture.complete(Unit)
      }

      override fun getBody(): CompletionStage<Unit> = bodyFuture

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
          ),
        )

      channel
        .trySend(startEvent)
        .onSuccess { logger.trace("Sent: start") }
        .onFailure { logger.error("Failed to send: start") }

      subscriber = Subscriber(channel)
      return subscriber!!
    }

  }

}

/** Rebuilds native wire fields while retaining the original body publisher and request settings. */
internal fun HttpRequest.withCredentials(credentials: RequestSecurity.Credentials): HttpRequest =
  copyToBuilder(includeHeaders = false).uri(credentials.uri).headers(credentials.headers).build()
