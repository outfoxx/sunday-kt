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
import io.outfoxx.sunday.MediaType.Companion.AnyText
import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.MediaType.Companion.WWWFormUrlEncoded
import io.outfoxx.sunday.RequestFactory
import io.outfoxx.sunday.SundayError
import io.outfoxx.sunday.SundayError.Reason.EventDecodingFailed
import io.outfoxx.sunday.SundayError.Reason.InvalidBaseUri
import io.outfoxx.sunday.SundayError.Reason.InvalidContentType
import io.outfoxx.sunday.SundayError.Reason.NoData
import io.outfoxx.sunday.SundayError.Reason.NoDecoder
import io.outfoxx.sunday.SundayError.Reason.NoSupportedAcceptTypes
import io.outfoxx.sunday.SundayError.Reason.NoSupportedContentTypes
import io.outfoxx.sunday.SundayError.Reason.ResponseDecodingFailed
import io.outfoxx.sunday.SundayError.Reason.UnexpectedEmptyResponse
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.Accept
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.HeaderParameters
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.ValueResponse
import io.outfoxx.sunday.http.contentLength
import io.outfoxx.sunday.http.contentType
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.StructuredMediaTypeDecoder
import io.outfoxx.sunday.mediatypes.codecs.TextMediaTypeDecoder
import io.outfoxx.sunday.mediatypes.codecs.URLQueryParamsEncoder
import io.outfoxx.sunday.mediatypes.codecs.decode
import io.outfoxx.sunday.problems.NonStandardStatus
import io.outfoxx.sunday.utils.from
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okio.buffer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.zalando.problem.DefaultProblem
import org.zalando.problem.Problem
import org.zalando.problem.Status
import org.zalando.problem.ThrowableProblem
import java.io.Closeable
import java.net.Authenticator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.time.Duration
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf

/**
 * JDK11 HTTP Client implementation of [RequestFactory].
 */
class JdkRequestFactory(
  private val baseURI: URITemplate,
  private val httpClient: HttpClient = defaultHttpClient(),
  val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default,
  val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default,
  private val requestTimeout: Duration = requestTimeoutDefault,
  private val eventRequestTimeout: Duration = EventSource.eventTimeoutDefault
) : RequestFactory(), Closeable {

  companion object {

    fun defaultHttpClient(authenticator: Authenticator? = null): HttpClient =
      HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .apply {
          authenticator?.let { authenticator(authenticator) }
        }
        .build()

    val requestTimeoutDefault: Duration = Duration.ofSeconds(10)

    private val logger = LoggerFactory.getLogger(JdkRequestFactory::class.java)

    private val unacceptableStatusCodes = 400 until 600
    private val emptyDataStatusCodes = setOf(204, 205)

    private fun URI.replaceQuery(rawQuery: String?): URI {
      val authorityPart = rawAuthority
      val pathPart = rawPath ?: "/"
      val queryPart = rawQuery?.let { "?$it" } ?: ""
      val fragmentPart = rawFragment?.let { "#$it" } ?: ""
      return URI.create("$scheme://$authorityPart$pathPart$queryPart$fragmentPart")
    }
  }

  private val problemTypes = mutableMapOf<String, KClass<out Problem>>()

  override fun registerProblem(typeId: String, problemType: KClass<out Problem>) {
    problemTypes[typeId] = problemType
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
    purpose: RequestPurpose
  ): Request {
    logger.trace("Building request")

    var uri =
      try {
        baseURI.resolve(pathTemplate, pathParameters).toURI()
      } catch (x: Throwable) {
        throw SundayError(InvalidBaseUri, cause = x)
      }

    if (!queryParameters.isNullOrEmpty()) {

      // Encode & add query parameters to url

      val urlQueryEncoder = mediaTypeEncoders.find(WWWFormUrlEncoded)
        ?: throw SundayError(NoDecoder, WWWFormUrlEncoded.value)

      urlQueryEncoder as? URLQueryParamsEncoder
        ?: throw SundayError(
          NoDecoder,
          "'$WWWFormUrlEncoded' encoder must implement ${URLQueryParamsEncoder::class.simpleName}"
        )

      val uriQuery = urlQueryEncoder.encodeQueryString(queryParameters)

      uri = uri.replaceQuery(uriQuery)
    }

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

    var requestBodyPublisher = body?.let {
      contentType ?: throw SundayError(NoSupportedContentTypes)

      val mediaTypeEncoder = mediaTypeEncoders.find(contentType)
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
          }
        )
        .build()

    logger.debug("Built request: {}", request)

    return JdkRequest(request, httpClient)
  }

  override suspend fun response(request: Request): Response {
    logger.debug("Initiating request")

    return request.execute()
  }

  override suspend fun <B : Any, R : Any> result(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters?,
    queryParameters: Parameters?,
    body: B?,
    contentTypes: List<MediaType>?,
    acceptTypes: List<MediaType>?,
    headers: Parameters?,
    resultType: KType
  ): ValueResponse<R> {

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

    if (unacceptableStatusCodes.contains(response.statusCode)) {
      throw parseFailure(response)
    }

    return ValueResponse(parseSuccess(response, resultType), response)
  }

  override fun eventSource(requestSupplier: suspend (Headers) -> Request): EventSource {

    return EventSource(requestSupplier)
  }

  override fun <D : Any> eventStream(
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

    awaitClose { eventSource.close() }
  }

  override fun close() {
    close(true)
  }

  override fun close(cancelOutstandingRequests: Boolean) {
    // TODO track outstanding requests and implement appropriately
  }

  private fun <T : Any> parseSuccess(response: Response, resultType: KType): T {
    logger.trace("Parsing success response")

    val body = response.body
    if (emptyDataStatusCodes.contains(response.statusCode)) {
      if (resultType != typeOf<Unit>()) {
        throw SundayError(UnexpectedEmptyResponse)
      }
      @Suppress("UNCHECKED_CAST")
      return Unit as T
    }

    if (body == null || response.contentLength == 0L) {
      throw SundayError(NoData)
    }

    val contentType =
      response.contentType?.let { MediaType.from(it.toString()) }
        ?: throw SundayError(InvalidContentType, response.contentType?.value ?: "")

    val contentTypeDecoder = mediaTypeDecoders.find(contentType)
      ?: throw SundayError(NoDecoder, contentType.value)

    try {

      return contentTypeDecoder.decode(body, resultType)
    } catch (x: Throwable) {
      throw SundayError(ResponseDecodingFailed, cause = x)
    }
  }

  private fun parseFailure(response: Response): ThrowableProblem {
    logger.trace("Parsing failure response")

    val status =
      try {
        Status.valueOf(response.statusCode)
      } catch (ignored: IllegalArgumentException) {
        NonStandardStatus(response)
      }

    val body = response.body

    return if (body != null && response.contentLength != 0L) {

      val contentType =
        response.contentType?.let { MediaType.from(it.toString()) }
          ?: MediaType.OctetStream

      if (!contentType.compatible(MediaType.Problem)) {
        val (responseText, responseData) =
          if (contentType.compatible(AnyText))
            body.readString(Charsets.from(contentType)) to null
          else
            null to body.readByteArray()

        Problem.builder()
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
      } else {

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
        val problemClass = (problemTypes[problemType] ?: DefaultProblem::class).createType()

        problemDecoder.decode(decoded, problemClass)
      }
    } else {
      Problem.valueOf(status)
    }
  }
}
