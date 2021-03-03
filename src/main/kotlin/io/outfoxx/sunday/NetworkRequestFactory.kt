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

import com.github.hal4j.uritemplate.URIBuilder
import io.outfoxx.sunday.MediaType.Companion.AnyText
import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.MediaType.Companion.ProblemJSON
import io.outfoxx.sunday.MediaType.Companion.WWWFormUrlEncoded
import io.outfoxx.sunday.SundayError.Reason.EventDecodingFailed
import io.outfoxx.sunday.SundayError.Reason.InvalidContentType
import io.outfoxx.sunday.SundayError.Reason.NoDecoder
import io.outfoxx.sunday.SundayError.Reason.ResponseDecodingFailed
import io.outfoxx.sunday.SundayError.Reason.UnexpectedEmptyResponse
import io.outfoxx.sunday.http.HeaderNames.Accept
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import org.slf4j.LoggerFactory
import org.zalando.problem.Problem
import org.zalando.problem.Status
import org.zalando.problem.ThrowableProblem
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass
import kotlin.reflect.cast

class NetworkRequestFactory(
  private val baseURI: URIBuilder,
  private val httpClient: OkHttpClient,
  private val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default,
  private val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default,
) : RequestFactory {

  companion object {
    private val logger = LoggerFactory.getLogger(NetworkRequestFactory::class.java)

    private val unacceptableStatusCodes = 400 until 600
    private val emptyDataStatusCodes = setOf(204, 205)
  }

  override suspend fun <B : Any> request(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters?,
    queryParameters: Parameters?,
    body: B?,
    bodyType: KClass<B>?,
    contentTypes: List<MediaType>?,
    acceptTypes: List<MediaType>?,
    headers: Headers?
  ): Request {
    logger.trace("Building request")

    val urlBuilder = baseURI.resolve(pathTemplate).expand(pathParameters).toBuilder()

    if (!queryParameters.isNullOrEmpty()) {

      // Encode & add query parameters to url

      val urlQueryEncoder = mediaTypeEncoders.find(WWWFormUrlEncoded) as URLEncoder

      urlBuilder.query().join(urlQueryEncoder.encodeQueryString(queryParameters))
    }

    val requestBuilder = Request.Builder().url(urlBuilder.toURI().toString())

    headers?.let { requestBuilder.headers(headers) }

    // Add `Accept` header based on accept types
    if (acceptTypes != null) {
      val supportedAcceptTypes = acceptTypes.filter(mediaTypeEncoders::supports)
      if (supportedAcceptTypes.isEmpty()) {
        throw IllegalArgumentException("Unsupported Accept header media types: $acceptTypes")
      }
      val accept = supportedAcceptTypes.joinToString(" , ") { it.toString() }

      requestBuilder.header(Accept, accept)
    }

    val contentType = contentTypes?.firstOrNull(mediaTypeEncoders::supports)

    // Add `Content-Type` header (even if body is null, to match any expected server requirements)
    contentType?.let { requestBuilder.addHeader(ContentType, contentType.toString()) }

    val requestBody = body?.let {
      requireNotNull(contentType) { "No supported Content-Type for request with body value" }

      val encodedBody = mediaTypeEncoders.find(contentType)?.encode(body)
        ?: throw IllegalArgumentException("No registered encoder for Content-Type $contentType")

      encodedBody.toRequestBody(contentType.value.toMediaType())
    }

    val request = requestBuilder.method(method.name, requestBody).build()

    logger.debug("Built request: {}", request)

    return request
  }

  override suspend fun response(request: Request): Response {
    logger.debug("Initiating request")

    val call = httpClient.newCall(request)

    return suspendCancellableCoroutine { continuation ->
      call.enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {

          continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {

          // Don't bother with resuming the continuation if it is already cancelled.
          if (continuation.isCancelled) return
          continuation.resumeWithException(e)
        }
      })
    }
  }

  override suspend fun <B : Any> response(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters?,
    queryParameters: Parameters?,
    body: B?,
    bodyType: KClass<B>?,
    contentTypes: List<MediaType>?,
    acceptTypes: List<MediaType>?,
    headers: Headers?
  ): Response {
    val request =
      request(method, pathTemplate, pathParameters, queryParameters, body, bodyType, contentTypes, acceptTypes, headers)

    return response(request)
  }

  override suspend fun <B : Any, D : Any> result(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters?,
    queryParameters: Parameters?,
    body: B?,
    bodyType: KClass<B>?,
    contentTypes: List<MediaType>?,
    acceptTypes: List<MediaType>?,
    headers: Headers?,
    resultType: KClass<D>
  ): D {

    val response =
      response(
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

    if (unacceptableStatusCodes.contains(response.code)) {
      throw parseFailure(response)
    }

    return parseSuccess(response, resultType)
  }

  override fun eventSource(requestSupplier: suspend (Headers) -> Request): EventSource {

    val callSupplier: suspend (Headers) -> Call = { headers ->
      val request = requestSupplier(headers).newBuilder()
      headers.forEach { (name, value) -> request.header(name, value) }
      httpClient.newCall(request.build())
    }

    return EventSource(callSupplier)
  }

  @ExperimentalCoroutinesApi
  override fun <D : Any> eventStream(
    eventTypes: Map<String, KClass<out D>>,
    requestSupplier: suspend (Headers) -> Request
  ): Flow<D> = callbackFlow {

    val jsonDecoder = mediaTypeDecoders.find(JSON) ?: throw SundayError(NoDecoder, JSON.value)
    jsonDecoder as TextMediaTypeDecoder

    val eventSource = eventSource(requestSupplier)

    eventTypes.forEach { (event, type) ->
      eventSource.addEventListener(event) { _, _, data ->
        try {

          val decodedEvent = jsonDecoder.decode(data ?: "", type)

          sendBlocking(decodedEvent)
        } catch (x: Throwable) {
          cancel("Event decoding failed", SundayError(EventDecodingFailed, cause = x))
        }
      }
    }

    eventSource.onerror = { _, error ->
      cancel("EventSource error encountered", error)
    }

    eventSource.connect()

    awaitClose { eventSource.close() }
  }

  override fun close(cancelOutstandingRequests: Boolean) {
    if (cancelOutstandingRequests) {
      httpClient.dispatcher.cancelAll()
    }
  }

  private fun <T : Any> parseSuccess(response: Response, resultType: KClass<T>): T {
    logger.trace("Parsing success response")

    val body = response.body
    if (emptyDataStatusCodes.contains(response.code) || body == null) {
      if (resultType != Unit::class) {
        throw SundayError(UnexpectedEmptyResponse)
      }
      return resultType.cast(Unit)
    }

    val contentType =
      body.contentType()?.let { MediaType.from(it.toString()) }
        ?: throw SundayError(InvalidContentType, body.contentType()?.toString() ?: "")

    val contentTypeDecoder = mediaTypeDecoders.find(contentType)
      ?: throw SundayError(NoDecoder, contentType.value)

    try {

      return contentTypeDecoder.decode(body.bytes(), resultType)
    } catch (x: Throwable) {
      throw SundayError(ResponseDecodingFailed, cause = x)
    }
  }

  private fun parseFailure(response: Response): ThrowableProblem {
    logger.trace("Parsing failure response")

    val status = Status.valueOf(response.code)

    val body = response.body

    return if (body != null) {

      val contentType =
        body.contentType()?.let { MediaType.from(it.toString()) }
          ?: throw SundayError(InvalidContentType, body.contentType()?.toString() ?: "")

      if (!contentType.compatible(ProblemJSON)) {
        val (detail, responseData) =
          if (contentType.compatible(AnyText))
            body.string() to null
          else
            null to body.bytes()

        Problem.builder()
          .withStatus(status)
          .withDetail(detail)
          .apply {
            if (responseData != null) {
              with("data", responseData)
            }
          }
          .build()
      } else {

        val mediaType =
          mediaTypeDecoders.find(ProblemJSON)
            ?: throw SundayError(NoDecoder, ProblemJSON.value)

        mediaType.decode(body.bytes(), ThrowableProblem::class)
      }
    } else {
      Problem.valueOf(status)
    }
  }
}
