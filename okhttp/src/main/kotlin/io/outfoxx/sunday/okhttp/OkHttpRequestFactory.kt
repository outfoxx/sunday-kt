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

import io.outfoxx.sunday.EventSource
import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.Companion.WWWFormUrlEncoded
import io.outfoxx.sunday.PathEncoder
import io.outfoxx.sunday.PathEncoders
import io.outfoxx.sunday.RequestFactory
import io.outfoxx.sunday.SundayError
import io.outfoxx.sunday.SundayError.Reason.InvalidBaseUri
import io.outfoxx.sunday.SundayError.Reason.NoDecoder
import io.outfoxx.sunday.SundayError.Reason.NoSupportedAcceptTypes
import io.outfoxx.sunday.SundayError.Reason.NoSupportedContentTypes
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.ACCEPT
import io.outfoxx.sunday.http.HeaderNames.CONTENT_TYPE
import io.outfoxx.sunday.http.HeaderParameters
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.URLQueryParamsEncoder
import io.outfoxx.sunday.problems.Problem
import io.outfoxx.sunday.problems.ProblemFactory
import kotlinx.io.readByteString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.Closeable
import kotlin.reflect.KClass

class OkHttpRequestFactory(
  private val baseURI: URITemplate,
  override val problemFactory: ProblemFactory,
  private val httpClient: OkHttpClient = OkHttpClient(),
  private val eventHttpClient: OkHttpClient = httpClient.reconfiguredForEvents(),
  override val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default,
  override val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default,
  override val pathEncoders: Map<KClass<*>, PathEncoder> = PathEncoders.default,
) : RequestFactory(),
  Closeable {

  companion object {

    private val logger = LoggerFactory.getLogger(OkHttpRequestFactory::class.java)
  }

  override val registeredProblemTypes: Map<String, KClass<out Problem>>
    get() = registeredProblemTypesStorage
  private val registeredProblemTypesStorage = mutableMapOf<String, KClass<out Problem>>()

  override fun registerProblem(
    typeId: String,
    problemType: KClass<out Problem>,
  ) {
    registeredProblemTypesStorage[typeId] = problemType
  }

  override suspend fun <B : Any> request(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters?,
    queryParameters: Parameters?,
    body: B?,
    contentTypes: List<MediaType>?,
    acceptTypes: List<MediaType>?,
    headers: Parameters?,
    purpose: RequestPurpose,
  ): Request {
    logger.trace("Building request")

    val urlBuilder =
      baseURI
        .resolve(pathTemplate, pathParameters, pathEncoders)
        .toURI()
        .toHttpUrlOrNull()
        ?.newBuilder()
        ?: throw SundayError(InvalidBaseUri)

    if (!queryParameters.isNullOrEmpty()) {
      // Encode & add query parameters to url

      val urlQueryEncoder =
        mediaTypeEncoders.find(WWWFormUrlEncoded)
          ?: throw SundayError(NoDecoder, WWWFormUrlEncoded.value)

      urlQueryEncoder as? URLQueryParamsEncoder
        ?: throw SundayError(
          NoDecoder,
          "'$WWWFormUrlEncoded' encoder must implement ${URLQueryParamsEncoder::class.simpleName}",
        )

      urlBuilder.encodedQuery(urlQueryEncoder.encodeQueryString(queryParameters))
    }

    val requestBuilder = okhttp3.Request.Builder().url(urlBuilder.build())

    HeaderParameters.encode(headers).forEach { (headerName, headerValue) ->
      requestBuilder.addHeader(headerName, headerValue)
    }

    // Add `Accept` header based on accepted types
    if (acceptTypes != null) {
      val supportedAcceptTypes = acceptTypes.filter(mediaTypeDecoders::supports)
      if (supportedAcceptTypes.isEmpty()) {
        throw SundayError(NoSupportedAcceptTypes)
      }

      val accept = supportedAcceptTypes.joinToString(" , ") { it.toString() }

      requestBuilder.header(ACCEPT, accept)
    }

    val contentType = contentTypes?.firstOrNull(mediaTypeEncoders::supports)

    // Add a `Content-Type` header (even if the body is null, to match any expected server requirements)
    contentType?.let { requestBuilder.addHeader(CONTENT_TYPE, contentType.toString()) }

    var requestBody =
      body?.let {
        contentType ?: throw SundayError(NoSupportedContentTypes)

        val mediaTypeEncoder =
          mediaTypeEncoders.find(contentType)
            ?: error("Cannot find encoder that was reported as supported")

        val encodedBody = mediaTypeEncoder.encode(body).readByteString().toByteArray()

        encodedBody.toRequestBody(contentType.value.toMediaType())
      }

    if (requestBody == null && method.bodyAllowed) {
      requestBody = byteArrayOf().toRequestBody()
    }

    val request =
      requestBuilder
        .method(method.name, requestBody)
        .build()

    logger.debug("Built request: {}", request)

    val httpClient =
      when (purpose) {
        RequestPurpose.Normal -> httpClient
        RequestPurpose.Events -> eventHttpClient
      }

    return OkHttpRequest(request, httpClient)
  }

  override suspend fun response(request: Request): Response {
    logger.debug("Initiating request")

    return request.execute()
  }

  override fun eventSource(requestSupplier: suspend (Headers) -> Request): EventSource =
    EventSource(requestSupplier, problemFactory)

  override fun close() {
    close(true)
  }

  override fun close(cancelOutstandingRequests: Boolean) {
    if (cancelOutstandingRequests) {
      httpClient.dispatcher.cancelAll()
      eventHttpClient.dispatcher.cancelAll()
    }
  }
}
