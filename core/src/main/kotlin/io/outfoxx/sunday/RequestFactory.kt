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

import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.SundayError.Reason.EventDecodingFailed
import io.outfoxx.sunday.SundayError.Reason.NoDecoder
import io.outfoxx.sunday.http.HeaderParameters
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.ResultResponse
import io.outfoxx.sunday.http.contentLength
import io.outfoxx.sunday.http.contentType
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.StructuredMediaTypeDecoder
import io.outfoxx.sunday.mediatypes.codecs.TextMediaTypeDecoder
import io.outfoxx.sunday.mediatypes.codecs.decode
import io.outfoxx.sunday.problems.NonStandardStatus
import io.outfoxx.sunday.utils.from
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okio.BufferedSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.zalando.problem.DefaultProblem
import org.zalando.problem.Problem
import org.zalando.problem.Status
import org.zalando.problem.StatusType
import org.zalando.problem.ThrowableProblem
import java.io.Closeable
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf

/**
 * Factory for requests, responses and event sources.
 */
abstract class RequestFactory : Closeable {

  companion object {

    private val logger = LoggerFactory.getLogger(RequestFactory::class.java)

    private val failureStatusCodes = 400 until 600
    private val emptyDataStatusCodes = setOf(204, 205)
  }

  /**
   * Purpose of the request.
   */
  enum class RequestPurpose {

    /**
     * Request will be used for normal RPC style calls.
     */
    Normal,

    /**
     * Request will be used for Server-Sent Events connections.
     */
    Events
  }

  /**
   * Register a type of [Problem] to allow decoding and throwing specific
   * exception instances of the provided type when returned in server responses.
   *
   * If a problem response is encountered that do not have a registered type, a generic
   * [Problem] will be returned/thrown.
   *
   * @param typeId Problem type id to register.
   * @param problemType [Problem] subclass to map to [typeId].
   */
  abstract fun registerProblem(typeId: String, problemType: KClass<out ThrowableProblem>)

  abstract val registeredProblemTypes: Map<String, KClass<out ThrowableProblem>>
  abstract val mediaTypeEncoders: MediaTypeEncoders
  abstract val mediaTypeDecoders: MediaTypeDecoders
  abstract val pathEncoders: Map<KClass<*>, PathEncoder>

  /**
   * Create a [Request].
   */
  suspend inline fun request(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ) = request(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    null as Unit?,
    contentTypes,
    acceptTypes,
    headers
  )

  /**
   * Create a [Request].
   */
  abstract suspend fun <B : Any> request(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
    purpose: RequestPurpose = RequestPurpose.Normal
  ): Request

  /**
   * Execute a [request][Request] and return the server's [response][Response].
   *
   * @param request Request used to generate the response.
   * @return [Response] returned from the executed [request].
   */
  abstract suspend fun response(request: Request): Response

  /**
   * Create and execute a [request][Request] created from the given request
   * parameters and return the server's [response][Response].
   *
   * @return [Response] returned from the generated request.
   */
  suspend inline fun response(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ) = response(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    null as Unit?,
    contentTypes,
    acceptTypes,
    headers
  )

  /**
   * Create and execute a [request][Request] created from the given request
   * parameters and return the server's [response][Response].
   *
   * @return [Response] returned from the generated request.
   */
  suspend inline fun <B : Any> response(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ): Response {
    val request =
      request(
        method,
        pathTemplate,
        pathParameters,
        queryParameters,
        body,
        contentTypes,
        acceptTypes,
        headers
      )

    return response(request)
  }

  /**
   * Create and execute a [request][Request] created from the given request
   * parameters and returns a result decoded from the server's response.
   *
   * @return Instance of [R] decoded from the HTTP response.
   */
  suspend inline fun <reified B : Any, reified R : Any> result(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
  ): R = result(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    body,
    contentTypes,
    acceptTypes,
    headers,
    typeOf<R>()
  )

  /**
   * Create and execute a [request][Request] created from the given request
   * parameters and returns a result decoded from the server's response.
   *
   * @return Instance of [R] decoded from the HTTP response.
   */
  suspend inline fun <reified R : Any> result(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
  ): R = result(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    null as Unit?,
    contentTypes,
    acceptTypes,
    headers,
    typeOf<R>()
  )

  /**
   * Create and execute a [request][Request] created from the given request
   * parameters and returns a result decoded from the server's response.
   *
   * @return Instance of [R] decoded from the HTTP response.
   */
  suspend fun <B : Any, R : Any> result(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
    resultType: KType
  ): R = resultResponse<B, R>(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    body,
    contentTypes,
    acceptTypes,
    headers,
    resultType,
  ).result

  /**
   * Create and execute a [request][Request] created from the given request
   * parameters and return the server's [response][Response] along with a result
   * decoded from the server's response.
   *
   * @return [ResultResponse] returned from the generated request.
   */
  suspend inline fun <reified B : Any, reified R : Any> resultResponse(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
  ): ResultResponse<R> = resultResponse(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    body,
    contentTypes,
    acceptTypes,
    headers,
    typeOf<R>()
  )

  /**
   * Create and execute a [request][Request] created from the given request
   * parameters and return the server's [response][Response] along with a result
   * decoded from the server's response.
   *
   * @return [ResultResponse] returned from the generated request.
   */
  suspend inline fun <reified R : Any> resultResponse(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
  ): ResultResponse<R> = resultResponse(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    null as Unit?,
    contentTypes,
    acceptTypes,
    headers,
    typeOf<R>()
  )

  /**
   * Create and execute a [request][Request] created from the given request
   * parameters and return the server's [response][Response] along with a result
   * decoded from the server's response.
   *
   * @return [ResultResponse] returned from the generated request.
   */
  suspend fun <B : Any, R : Any> resultResponse(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
    resultType: KType
  ): ResultResponse<R> {

    val response =
      response(
        method,
        pathTemplate,
        pathParameters,
        queryParameters,
        body,
        contentTypes,
        acceptTypes,
        headers
      )

    if (isFailureResponse(response)) {
      logger.trace("Parsing failure response")

      throw parseFailure(response)
    }

    logger.trace("Parsing success response")

    return ResultResponse(
      parseSuccess(
        response,
        resultType,
      ),
      response
    )
  }

  /**
   * Creates an [EventSource] that uses the provided request parameters to supply
   * the [Request] used for connecting to the Server-Sent Events stream.
   *
   * @return [EventSource] instance.
   */
  fun <B : Any> eventSource(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ): EventSource = eventSource {
    request(
      method,
      pathTemplate,
      pathParameters,
      queryParameters,
      body,
      contentTypes,
      acceptTypes,
      headers
    )
  }

  /**
   * Creates an [EventSource] that uses the provided request parameters to supply
   * the [Request] used for connecting to the Server-Sent Events stream.
   *
   * @return [EventSource] instance.
   */
  fun eventSource(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ): EventSource = eventSource { eventSourceHeaders ->
    request(
      method,
      pathTemplate,
      pathParameters,
      queryParameters,
      null as Unit?,
      contentTypes,
      acceptTypes,
      eventSourceHeaders.let { esHeaders ->
        (headers?.let { esHeaders + HeaderParameters.encode(headers) } ?: esHeaders).toMap()
      }
    )
  }

  /**
   * Creates an [EventSource] that uses the provided [requestSupplier] to supply the [Request]
   * used for connecting to the Server-Sent Events stream.
   *
   * @return [EventSource] instance.
   */
  protected abstract fun eventSource(requestSupplier: suspend (Headers) -> Request): EventSource

  /**
   * Creates an [Flow] of events that uses the provided request parameters to supply
   * the [Request] used for connecting to the Server-Sent Events stream.
   *
   * @param decoder Function used to decode events from the SSE stream.
   * @return [Flow] of events.
   */
  fun <B : Any, D : Any> eventStream(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
    decoder: (TextMediaTypeDecoder, String?, String?, String, Logger) -> D?,
  ): Flow<D> = eventStream(decoder) {
    request(
      method,
      pathTemplate,
      pathParameters,
      queryParameters,
      body,
      contentTypes,
      acceptTypes,
      headers
    )
  }

  /**
   * Creates an [Flow] of events that uses the provided request parameters to supply
   * the [Request] used for connecting to the Server-Sent Events stream.
   *
   * @param decoder Function used to decode events from the SSE stream.
   * @return [Flow] of events.
   */
  fun <D : Any> eventStream(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
    decoder: (TextMediaTypeDecoder, String?, String?, String, Logger) -> D?,
  ): Flow<D> = eventStream(decoder) {
    request(
      method,
      pathTemplate,
      pathParameters,
      queryParameters,
      null as Unit?,
      contentTypes,
      acceptTypes,
      headers
    )
  }

  /**
   * Creates a [Flow] of events that uses the provided [requestSupplier] to supply the [Request]
   * used for connecting to the Server-Sent Events stream.
   *
   * @param decoder Function used to decode events from the SSE stream.
   * @param requestSupplier Function that provides [Request] for connecting to the SSE stream.
   * @return [Flow] of events.
   */
  fun <D : Any> eventStream(
    decoder: (TextMediaTypeDecoder, String?, String?, String, Logger) -> D?,
    requestSupplier: suspend (Headers) -> Request
  ): Flow<D> = callbackFlow {

    val jsonDecoder = mediaTypeDecoders.find(JSON) ?: throw SundayError(NoDecoder, JSON.value)
    jsonDecoder as TextMediaTypeDecoder

    val eventSource = eventSource(requestSupplier)

    eventSource.onMessage = { event ->

      val data = event.data
      if (data != null) {

        try {

          val decodedEvent = decoder(jsonDecoder, event.event, event.id, data, logger)
          if (decodedEvent != null) {

            trySendBlocking(decodedEvent)
              .onFailure {
                cancel("Event send failed", it)
              }

          }

        } catch (x: Throwable) {
          cancel("Event decoding failed", SundayError(EventDecodingFailed, cause = x))
        }

      }

    }

    eventSource.onError = { error ->
      logger.warn("EventSource error encountered", error)
    }

    eventSource.connect()

    awaitClose {
      logger.debug("Stream closed or canceled")

      eventSource.close()
    }
  }

  abstract fun close(cancelOutstandingRequests: Boolean)

  private fun isFailureResponse(response: Response) =
    failureStatusCodes.contains(response.statusCode)

  private fun <T : Any> parseSuccess(response: Response, resultType: KType): T {

    val body = response.body
    if (emptyDataStatusCodes.contains(response.statusCode)) {
      if (resultType != typeOf<Unit>()) {
        throw SundayError(SundayError.Reason.UnexpectedEmptyResponse)
      }
      @Suppress("UNCHECKED_CAST")
      return Unit as T
    }

    if (body == null || response.contentLength == 0L) {
      throw SundayError(SundayError.Reason.NoData)
    }

    val contentType =
      response.contentType?.let { MediaType.from(it.toString()) }
        ?: throw SundayError(
          SundayError.Reason.InvalidContentType,
          response.contentType?.value ?: ""
        )

    val contentTypeDecoder = mediaTypeDecoders.find(contentType)
      ?: throw SundayError(NoDecoder, contentType.value)

    try {

      return contentTypeDecoder.decode(body, resultType)
    } catch (x: Throwable) {
      throw SundayError(SundayError.Reason.ResponseDecodingFailed, cause = x)
    }
  }

  private fun parseFailure(response: Response): ThrowableProblem {

    val status =
      try {
        Status.valueOf(response.statusCode)
      } catch (ignored: IllegalArgumentException) {
        NonStandardStatus(response)
      }

    return parseFailureResponseBody(response, status)
  }

  private fun parseFailureResponseBody(response: Response, status: StatusType): ThrowableProblem {

    val body = response.body

    return if (body != null && response.contentLength != 0L) {

      val contentType =
        response.contentType?.let { MediaType.from(it.toString()) }
          ?: MediaType.OctetStream

      if (!contentType.compatible(MediaType.Problem)) {
        parseUnknownFailureResponseBody(contentType, body, status)
      } else {
        parseProblemResponseBody(body)
      }
    } else {
      Problem.valueOf(status)
    }
  }

  private fun parseProblemResponseBody(body: BufferedSource): ThrowableProblem {
    val problemDecoder =
      mediaTypeDecoders.find(MediaType.Problem)
        ?: throw SundayError(NoDecoder, MediaType.Problem.value)

    problemDecoder as? StructuredMediaTypeDecoder
      ?: throw SundayError(
        NoDecoder,
        "'${MediaType.Problem}' decoder must support structured decoding"
      )

    val decoded: Map<String, Any> = problemDecoder.decode(body)

    val problemType = decoded["type"]?.toString() ?: ""
    val problemClass = (registeredProblemTypes[problemType] ?: DefaultProblem::class).createType()

    return problemDecoder.decode(decoded, problemClass)
  }

  private fun parseUnknownFailureResponseBody(
    contentType: MediaType,
    body: BufferedSource,
    status: StatusType
  ): ThrowableProblem {
    val (responseText, responseData) =
      if (contentType.compatible(MediaType.AnyText))
        body.readString(Charsets.from(contentType)) to null
      else
        null to body.readByteArray()

    return Problem.builder()
      .withStatus(status)
      .withTitle(status.reasonPhrase)
      .apply {
        if (responseText != null) {
          with("responseText", responseText)
        }
        if (responseData != null) {
          with("responseData", responseData)
        }
      }
      .build()
  }

}
