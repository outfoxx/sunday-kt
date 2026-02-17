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
import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.http.contentLength
import io.outfoxx.sunday.http.contentType
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.StructuredMediaTypeDecoder
import io.outfoxx.sunday.mediatypes.codecs.TextMediaTypeDecoder
import io.outfoxx.sunday.mediatypes.codecs.decode
import io.outfoxx.sunday.problems.Problem
import io.outfoxx.sunday.problems.ProblemFactory
import io.outfoxx.sunday.problems.ProblemFactory.Descriptor
import io.outfoxx.sunday.utils.from
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readString
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.URI
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
    Events,
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
  abstract fun registerProblem(
    typeId: String,
    problemType: KClass<out Problem>,
  )

  abstract val registeredProblemTypes: Map<String, KClass<out Problem>>
  abstract val mediaTypeEncoders: MediaTypeEncoders
  abstract val mediaTypeDecoders: MediaTypeDecoders
  abstract val pathEncoders: Map<KClass<*>, PathEncoder>
  abstract val problemFactory: ProblemFactory

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
    headers: Parameters? = null,
  ) = request(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    null as Unit?,
    contentTypes,
    acceptTypes,
    headers,
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
    purpose: RequestPurpose = RequestPurpose.Normal,
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
    headers: Parameters? = null,
  ) = response(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    null as Unit?,
    contentTypes,
    acceptTypes,
    headers,
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
    headers: Parameters? = null,
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
        headers,
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
  ): R =
    result(
      method,
      pathTemplate,
      pathParameters,
      queryParameters,
      body,
      contentTypes,
      acceptTypes,
      headers,
      typeOf<R>(),
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
  ): R =
    result(
      method,
      pathTemplate,
      pathParameters,
      queryParameters,
      null as Unit?,
      contentTypes,
      acceptTypes,
      headers,
      typeOf<R>(),
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
    resultType: KType,
  ): R =
    resultResponse<B, R>(
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
  ): ResultResponse<R> =
    resultResponse(
      method,
      pathTemplate,
      pathParameters,
      queryParameters,
      body,
      contentTypes,
      acceptTypes,
      headers,
      typeOf<R>(),
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
  ): ResultResponse<R> =
    resultResponse(
      method,
      pathTemplate,
      pathParameters,
      queryParameters,
      null as Unit?,
      contentTypes,
      acceptTypes,
      headers,
      typeOf<R>(),
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
    resultType: KType,
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
        headers,
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
      response,
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
    headers: Parameters? = null,
  ): EventSource =
    eventSource {
      request(
        method,
        pathTemplate,
        pathParameters,
        queryParameters,
        body,
        contentTypes,
        acceptTypes,
        headers,
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
    headers: Parameters? = null,
  ): EventSource =
    eventSource { eventSourceHeaders ->
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
        },
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
  ): Flow<D> =
    eventStream(decoder) {
      request(
        method,
        pathTemplate,
        pathParameters,
        queryParameters,
        body,
        contentTypes,
        acceptTypes,
        headers,
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
  ): Flow<D> =
    eventStream(decoder) {
      request(
        method,
        pathTemplate,
        pathParameters,
        queryParameters,
        null as Unit?,
        contentTypes,
        acceptTypes,
        headers,
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
    requestSupplier: suspend (Headers) -> Request,
  ): Flow<D> =
    callbackFlow {
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

  private fun isFailureResponse(response: Response) = failureStatusCodes.contains(response.statusCode)

  private fun <T : Any> parseSuccess(
    response: Response,
    resultType: KType,
  ): T {
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
          response.contentType?.value ?: "<none provided>",
        )

    val contentTypeDecoder =
      mediaTypeDecoders.find(contentType)
        ?: throw SundayError(NoDecoder, contentType.value)

    try {
      return contentTypeDecoder.decode(body, resultType)
    } catch (x: Throwable) {
      throw SundayError(SundayError.Reason.ResponseDecodingFailed, cause = x)
    }
  }

  private fun parseFailure(response: Response): Problem =
    parseFailureResponseBody(response, Status.valueOf(response.statusCode))

  private fun parseFailureResponseBody(
    response: Response,
    status: Status,
  ): Problem {
    val body = response.body

    return if (body != null && response.contentLength != 0L) {
      val contentType =
        response.contentType?.let { MediaType.from(it.toString()) }
          ?: MediaType.OctetStream

      if (!contentType.compatible(MediaType.Problem)) {
        parseUnknownFailureResponseBody(contentType, body, status)
      } else {
        parseProblemResponseBody(body, status)
      }
    } else {
      problemFactory.from(
        Descriptor(
          status = status.code,
          title = defaultTitleForType(URI.create("about:blank"), status),
        ),
      )
    }
  }

  private fun parseProblemResponseBody(
    body: Source,
    status: Status,
  ): Problem {
    val problemDecoder =
      mediaTypeDecoders.find(MediaType.Problem)
        ?: throw SundayError(NoDecoder, MediaType.Problem.value)

    problemDecoder as? StructuredMediaTypeDecoder
      ?: throw SundayError(
        NoDecoder,
        "'${MediaType.Problem}' decoder must support structured decoding",
      )

    val decoded: Map<String, Any> = problemDecoder.decode(body)

    val problemType = decoded["type"]?.toString() ?: ""
    return registeredProblemTypes[problemType]
      ?.createType()
      ?.let {
        problemDecoder.decode(decoded, it)
      }
      ?: problemFactory.from(toDescriptor(decoded, status))
  }

  private fun parseUnknownFailureResponseBody(
    contentType: MediaType,
    body: Source,
    status: Status,
  ): Problem {
    val (responseText, responseData) =
      if (contentType.compatible(MediaType.AnyText)) {
        body.readString(Charsets.from(contentType)) to null
      } else {
        null to body.readByteArray()
      }

    val extensions =
      buildMap<String, Any?> {
        if (responseText != null) {
          put("responseText", responseText)
        }
        if (responseData != null) {
          put("responseData", responseData)
        }
      }
    return problemFactory.from(
      Descriptor(
        status = status.code,
        title = defaultTitleForType(URI.create("about:blank"), status),
        extensions = extensions,
      ),
    )
  }

  private fun toDescriptor(
    decoded: Map<String, Any>,
    fallbackStatus: Status,
  ): Descriptor {
    val type =
      decoded["type"]
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?.let(URI::create)
        ?: URI.create("about:blank")

    val statusCode =
      when (val rawStatus = decoded["status"]) {
        is Number -> rawStatus.toInt()
        is String -> rawStatus.toIntOrNull()
        is Map<*, *> -> {
          val code =
            when (val rawCode = rawStatus["code"]) {
              is Number -> rawCode.toInt()
              is String -> rawCode.toIntOrNull()
              else -> null
            }
          code
        }

        else -> null
      }

    val status =
      when (val rawStatus = decoded["status"]) {
        is Map<*, *> -> {
          val reason = rawStatus["reasonPhrase"]?.toString()
          when {
            statusCode == null -> null
            reason.isNullOrBlank() -> Status.valueOf(statusCode)
            else -> Status.valueOf(statusCode, reason)
          }
        }

        else -> statusCode?.let(Status::valueOf)
      } ?: fallbackStatus

    val title =
      decoded["title"]?.toString()
        // Fallback to status reason but only if type == null or "about:blank" (matching RFC)
        ?: defaultTitleForType(type, status)

    val detail = decoded["detail"]?.toString()

    val instance =
      decoded["instance"]
        ?.toString()
        ?.takeIf { it.isNotBlank() }
        ?.let(URI::create)

    val extensions =
      decoded.filterKeys { it !in setOf("type", "title", "status", "detail", "instance") }

    return Descriptor(
      type = type,
      title = title,
      status = status.code,
      detail = detail,
      instance = instance,
      extensions = extensions,
    )
  }

  private fun defaultTitleForType(
    type: URI?,
    status: Status,
  ): String? = if (type == null || type == URI.create("about:blank")) status.reasonPhrase else null

}
