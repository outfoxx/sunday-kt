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

import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.mediatypes.codecs.TextMediaTypeDecoder
import kotlinx.coroutines.flow.Flow
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.slf4j.Logger
import org.zalando.problem.Problem
import java.io.Closeable
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

abstract class RequestFactory : Closeable {

  abstract fun registerProblem(typeId: String, problemType: KClass<out Problem>)

  suspend inline fun <reified B : Any> request(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ) = request(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    body,
    typeOf<B>(),
    contentTypes,
    acceptTypes,
    headers
  )

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
    typeOf<Unit>(),
    contentTypes,
    acceptTypes,
    headers
  )

  abstract suspend fun <B : Any> request(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    bodyType: KType? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ): Request

  abstract suspend fun response(request: Request): Response

  suspend inline fun <reified B : Any> response(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ) = response(
    method,
    pathTemplate,
    pathParameters,
    queryParameters,
    body,
    typeOf<B>(),
    contentTypes,
    acceptTypes,
    headers
  )

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
    typeOf<Unit>(),
    contentTypes,
    acceptTypes,
    headers
  )

  abstract suspend fun <B : Any> response(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    bodyType: KType?,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ): Response

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
    typeOf<B>(),
    contentTypes,
    acceptTypes,
    headers,
    typeOf<R>()
  )

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
    typeOf<Unit>(),
    contentTypes,
    acceptTypes,
    headers,
    typeOf<R>()
  )

  abstract suspend fun <B : Any, R : Any> result(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    bodyType: KType,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null,
    resultType: KType
  ): R

  fun <B : Any> eventSource(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    bodyType: KType? = null,
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
      bodyType,
      contentTypes,
      acceptTypes,
      headers
    )
  }

  fun eventSource(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Parameters? = null
  ): EventSource = eventSource { eentSourceHeaders ->
    request(
      method,
      pathTemplate,
      pathParameters,
      queryParameters,
      null as Unit?,
      typeOf<Unit>(),
      contentTypes,
      acceptTypes,
      eentSourceHeaders.toMultimap().let { eventSourceHeaderParams ->
        headers?.let { headers + eventSourceHeaderParams } ?: eventSourceHeaderParams
      }
    )
  }

  protected abstract fun eventSource(requestSupplier: suspend (Headers) -> Request): EventSource

  fun <B : Any, D : Any> eventStream(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    bodyType: KType? = null,
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
      bodyType,
      contentTypes,
      acceptTypes,
      headers
    )
  }

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
      typeOf<Unit>(),
      contentTypes,
      acceptTypes,
      headers
    )
  }

  protected abstract fun <D : Any> eventStream(
    decoder: (TextMediaTypeDecoder, String?, String?, String, Logger) -> D?,
    requestSupplier: suspend (Headers) -> Request,
  ): Flow<D>

  abstract fun close(cancelOutstandingRequests: Boolean)
}
