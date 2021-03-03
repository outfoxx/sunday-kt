package io.outfoxx.sunday

import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import kotlinx.coroutines.flow.Flow
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import kotlin.reflect.KClass

interface RequestFactory {

  suspend fun <B : Any> request(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    bodyType: KClass<B>? = null,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Headers? = null
  ): Request

  suspend fun response(request: Request): Response

  suspend fun <B : Any> response(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    bodyType: KClass<B>?,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Headers? = null
  ): Response

  suspend fun <B : Any, R : Any> result(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters? = null,
    queryParameters: Parameters? = null,
    body: B? = null,
    bodyType: KClass<B>?,
    contentTypes: List<MediaType>? = null,
    acceptTypes: List<MediaType>? = null,
    headers: Headers? = null,
    resultType: KClass<R>
  ): R

  fun eventSource(requestSupplier: suspend (Headers) -> Request): EventSource

  fun <D : Any> eventStream(
    eventTypes: Map<String, KClass<out D>>,
    requestSupplier: suspend (Headers) -> Request
  ): Flow<D>

  fun close(cancelOutstandingRequests: Boolean)

}
