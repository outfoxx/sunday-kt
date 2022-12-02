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

import io.outfoxx.sunday.http.HeaderParameters
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.ValueResponse
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.TextMediaTypeDecoder
import kotlinx.coroutines.flow.Flow
import org.slf4j.Logger
import org.zalando.problem.Problem
import org.zalando.problem.ThrowableProblem
import java.io.Closeable
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Factory for requests, responses and event sources.
 */
abstract class RequestFactory : Closeable {

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
   * parameters and return the server's [response][Response] along with a value
   * decoded based on the response's Content-Type.
   *
   * @return [ValueResponse] returned from the generated request.
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
  ): ValueResponse<R> = result(
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
   * parameters and return the server's [response][Response] along with a value
   * decoded based on the response's Content-Type.
   *
   * @return [ValueResponse] returned from the generated request.
   */
  suspend inline fun <reified R : Any> result(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
  ): ValueResponse<R> = result(
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
   * parameters and return the server's [response][Response] along with a value
   * decoded based on the response's Content-Type.
   *
   * @return [ValueResponse] returned from the generated request.
   */
  abstract suspend fun <B : Any, R : Any> result(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
    resultType: KType
  ): ValueResponse<R>

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
  protected abstract fun <D : Any> eventStream(
    decoder: (TextMediaTypeDecoder, String?, String?, String, Logger) -> D?,
    requestSupplier: suspend (Headers) -> Request,
  ): Flow<D>

  abstract fun close(cancelOutstandingRequests: Boolean)
}
