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
import io.outfoxx.sunday.http.HeaderNames.Accept
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.HeaderParameters
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.URLQueryParamsEncoder
import okio.buffer
import org.slf4j.LoggerFactory
import org.zalando.problem.ThrowableProblem
import java.io.Closeable
import java.net.Authenticator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.time.Duration
import kotlin.reflect.KClass

/**
 * JDK11 HTTP Client implementation of [RequestFactory].
 */
class JdkRequestFactory(
  private val baseURI: URITemplate,
  private val httpClient: HttpClient = defaultHttpClient(),
  private val adapter: suspend (HttpRequest) -> HttpRequest = { it },
  override val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default,
  override val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default,
  override val pathEncoders: Map<KClass<*>, PathEncoder> = PathEncoders.default,
  private val requestTimeout: Duration = requestTimeoutDefault,
  private val eventRequestTimeout: Duration = EventSource.eventTimeoutDefault,
) : RequestFactory(),
  Closeable {

  companion object {

    fun defaultHttpClient(authenticator: Authenticator? = null): HttpClient =
      HttpClient
        .newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .apply {
          authenticator?.let { authenticator(authenticator) }
        }.build()

    val requestTimeoutDefault: Duration = Duration.ofSeconds(10)

    private val logger = LoggerFactory.getLogger(JdkRequestFactory::class.java)

    private fun URI.replaceQuery(rawQuery: String?): URI {
      val authorityPart = rawAuthority
      val pathPart = rawPath ?: "/"
      val queryPart = rawQuery?.let { "?$it" } ?: ""
      val fragmentPart = rawFragment?.let { "#$it" } ?: ""
      return URI.create("$scheme://$authorityPart$pathPart$queryPart$fragmentPart")
    }
  }

  override val registeredProblemTypes: Map<String, KClass<out ThrowableProblem>>
    get() = registeredProblemTypesStorage
  private val registeredProblemTypesStorage = mutableMapOf<String, KClass<out ThrowableProblem>>()

  override fun registerProblem(
    typeId: String,
    problemType: KClass<out ThrowableProblem>,
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

    val uri = uri(pathTemplate, pathParameters, queryParameters)

    val requestBuilder = HttpRequest.newBuilder(uri)

    HeaderParameters.encode(headers).forEach { (headerName, headerValue) ->
      requestBuilder.header(headerName, headerValue)
    }

    // Add `Accept` header based on accept types
    if (acceptTypes != null) {
      val supportedAcceptTypes = acceptTypes.filter(mediaTypeDecoders::supports)
      if (supportedAcceptTypes.isEmpty()) {
        throw SundayError(NoSupportedAcceptTypes)
      }

      val accept = supportedAcceptTypes.joinToString(" , ") { it.toString() }

      requestBuilder.header(Accept, accept)
    }

    val contentType = contentTypes?.firstOrNull(mediaTypeEncoders::supports)

    // Add `Content-Type` header (even if body is null, to match any expected server requirements)
    contentType?.let { requestBuilder.header(ContentType, contentType.toString()) }

    var requestBodyPublisher =
      body?.let {
        contentType ?: throw SundayError(NoSupportedContentTypes)

        val mediaTypeEncoder =
          mediaTypeEncoders.find(contentType)
            ?: error("Cannot find encoder that was reported as supported")

        val encodedBody = mediaTypeEncoder.encode(body)

        BodyPublishers.ofInputStream { encodedBody.buffer().inputStream() }
      }

    if (requestBodyPublisher == null && method.requiresBody) {
      requestBodyPublisher = BodyPublishers.ofByteArray(byteArrayOf())
    }

    val request =
      requestBuilder
        .method(method.name, requestBodyPublisher ?: BodyPublishers.noBody())
        .timeout(
          when (purpose) {
            RequestPurpose.Normal -> requestTimeout
            RequestPurpose.Events -> eventRequestTimeout
          },
        ).build()

    logger.debug("Built request: {}", request)

    return JdkRequest(
      adapter.invoke(request),
      httpClient,
    )
  }

  private fun uri(
    pathTemplate: String,
    pathParameters: Parameters?,
    queryParameters: Parameters?,
  ): URI? {
    val uri =
      try {
        baseURI.resolve(pathTemplate, pathParameters, pathEncoders).toURI()
      } catch (x: Throwable) {
        throw SundayError(InvalidBaseUri, cause = x)
      }

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

      val uriQuery = urlQueryEncoder.encodeQueryString(queryParameters)

      return uri.replaceQuery(uriQuery)
    } else {
      return uri
    }
  }

  override suspend fun response(request: Request): Response {
    logger.debug("Initiating request")

    return request.execute()
  }

  override fun eventSource(requestSupplier: suspend (Headers) -> Request): EventSource = EventSource(requestSupplier)

  override fun close() {
    close(true)
  }

  override fun close(cancelOutstandingRequests: Boolean) {
    // TODO track outstanding requests and implement appropriately
  }
}
