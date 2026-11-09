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

import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.Companion.WWWFormUrlEncoded
import io.outfoxx.sunday.ParameterValidator
import io.outfoxx.sunday.PathEncoder
import io.outfoxx.sunday.PathEncoders
import io.outfoxx.sunday.PayloadValidator
import io.outfoxx.sunday.RequestAdapter
import io.outfoxx.sunday.RequestExecution
import io.outfoxx.sunday.ResponseAdapter
import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.SundayError
import io.outfoxx.sunday.SundayError.Reason.InvalidBaseUri
import io.outfoxx.sunday.SundayError.Reason.NoDecoder
import io.outfoxx.sunday.SundayError.Reason.NoSupportedAcceptTypes
import io.outfoxx.sunday.SundayError.Reason.NoSupportedContentTypes
import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.ACCEPT
import io.outfoxx.sunday.http.HeaderNames.CONTENT_TYPE
import io.outfoxx.sunday.http.HeaderParameters
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.isCredentialed
import io.outfoxx.sunday.http.redirectedRequest
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.URLQueryParamsEncoder
import io.outfoxx.sunday.problems.Problem
import io.outfoxx.sunday.problems.ProblemFactory
import io.outfoxx.sunday.security.AuthenticationRecoveryBudget
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.SecurityContext
import io.outfoxx.sunday.security.TokenProvider
import io.outfoxx.sunday.security.withSecurityContext
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.Authenticator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.time.Duration
import kotlin.reflect.KClass

/**
 * JDK HTTP Client implementation of [Transport].
 *
 * Custom clients must use [HttpClient.Redirect.NEVER]; [redirectPolicy] controls redirects after response adaptation.
 * The legacy native [adapter] is composed before [requestAdapter], once per attempt or export.
 */
class JdkTransport(
  private val baseURI: URITemplate,
  override val problemFactory: ProblemFactory,
  httpClient: HttpClient? = null,
  adapter: (suspend (HttpRequest) -> HttpRequest)? = null,
  override val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default,
  override val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default,
  override val pathEncoders: Map<KClass<*>, PathEncoder> = PathEncoders.default,
  private val requestTimeout: Duration = requestTimeoutDefault,
  private val eventRequestTimeout: Duration? = null,
  tokenProviders: Map<String, TokenProvider> = emptyMap(),
  requestAdapter: RequestAdapter? = null,
  responseAdapter: ResponseAdapter? = null,
  private val redirectPolicy: HttpClient.Redirect = httpClient?.followRedirects() ?: HttpClient.Redirect.NORMAL,
) : Transport<HttpRequest, java.net.http.HttpResponse<kotlinx.io.Source>>(),
  Closeable {

  private val httpClient = httpClient ?: defaultHttpClient(allowRedirects = false)
  private val execution =
    RequestExecution<HttpRequest, java.net.http.HttpResponse<kotlinx.io.Source>>(
      tokenProviders,
      requestAdapter,
      responseAdapter,
      adapter?.let { native -> { request -> request.copy(transport = native(request.transport)) } },
    )

  companion object {

    fun defaultHttpClient(
      authenticator: Authenticator? = null,
      allowRedirects: Boolean = true,
    ): HttpClient =
      HttpClient
        .newBuilder()
        .followRedirects(if (allowRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
        .apply {
          authenticator?.let { authenticator(authenticator) }
        }.build()

    val requestTimeoutDefault: Duration = Duration.ofSeconds(10)

    private val logger = LoggerFactory.getLogger(JdkTransport::class.java)

    private fun URI.replaceQuery(rawQuery: String?): URI {
      val authorityPart = rawAuthority
      val pathPart = rawPath ?: "/"
      val queryPart = rawQuery?.let { "?$it" } ?: ""
      val fragmentPart = rawFragment?.let { "#$it" } ?: ""
      return URI.create("$scheme://$authorityPart$pathPart$queryPart$fragmentPart")
    }
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

  override suspend fun <B : Any> transportRequest(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters?,
    queryParameters: Parameters?,
    body: B?,
    contentTypes: List<MediaType>?,
    acceptTypes: List<MediaType>?,
    headers: Parameters?,
    purpose: RequestPurpose,
    requestValidation: PayloadValidator<B>?,
    parameterValidation: ParameterValidator?,
  ): Request<HttpRequest> {
    parameterValidation?.validateRequest()
    logger.trace("Building request")

    val uri = uri(pathTemplate, pathParameters, queryParameters)

    val requestBuilder = HttpRequest.newBuilder(uri)

    HeaderParameters.encode(headers).forEach { (headerName, headerValue) ->
      requestBuilder.header(headerName, headerValue)
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

    val streamingBody = body as? StreamingBody
    val contentType =
      if (streamingBody != null) {
        contentTypes?.firstOrNull()
      } else {
        contentTypes?.firstOrNull(mediaTypeEncoders::supports)
      }

    // Add a `Content-Type` header (even if the body is null, to match any expected server requirements)
    contentType?.let { requestBuilder.header(CONTENT_TYPE, contentType.toString()) }

    var requestBodyPublisher =
      streamingBody?.let {
        contentType ?: throw SundayError(NoSupportedContentTypes)

        streamingBody.bodyPublisher()
      } ?: body?.let {
        contentType ?: throw SundayError(NoSupportedContentTypes)

        val mediaTypeEncoder =
          mediaTypeEncoders.find(contentType)
            ?: error("Cannot find encoder that was reported as supported")

        requestValidation?.validate(body)
        val encodedBody = mediaTypeEncoder.encode(body).readByteArray()

        BodyPublishers.ofByteArray(encodedBody)
      }

    if (requestBodyPublisher == null && method.bodyAllowed) {
      requestBodyPublisher = BodyPublishers.ofByteArray(byteArrayOf())
    }

    val timeout =
      when (purpose) {
        RequestPurpose.Normal, RequestPurpose.Export -> requestTimeout
        RequestPurpose.Events -> eventRequestTimeout
      }
    timeout?.let(requestBuilder::timeout)

    val request =
      requestBuilder
        .method(method.name, requestBodyPublisher ?: BodyPublishers.noBody())
        .build()

    logger.debug("Built request: {}", request)

    val prepared = JdkRequest(request)
    return if (purpose == RequestPurpose.Export) execution.export(prepared) else prepared
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

  override suspend fun authorize(
    request: Request<HttpRequest>,
    bindings: List<SecurityBinding>,
    managed: Boolean,
  ): Request<HttpRequest> {
    val selected = request.withSecurityContext(SecurityContext(bindings.toList()))
    return if (managed) selected else execution.export(selected)
  }

  override suspend fun transportResponse(
    request: Request<HttpRequest>,
  ): Response<java.net.http.HttpResponse<kotlinx.io.Source>> {
    logger.debug("Initiating request")

    return execution.execute(request, ::send, ::redirect)
  }

  override fun start(
    request: Request<HttpRequest>,
    budget: AuthenticationRecoveryBudget,
  ) = execution.start(request, budget, ::send, ::redirect)

  private suspend fun send(request: Request<HttpRequest>): JdkResponse {
    require(httpClient.followRedirects() == HttpClient.Redirect.NEVER) {
      "Transport-managed JDK execution requires a client with Redirect.NEVER"
    }
    if (request.isCredentialed) {
      require(httpClient.authenticator().isEmpty && httpClient.cookieHandler().isEmpty) {
        "Credentialed JDK execution requires a client without ambient authentication or cookies"
      }
    }
    return httpClient.executeRequest(request.transport.withBodyRepeatability(request.isBodyRepeatable))
  }

  private fun redirect(
    prepared: Request<HttpRequest>,
    response: Response<java.net.http.HttpResponse<kotlinx.io.Source>>,
    sent: Request<*>,
  ): Request<HttpRequest>? {
    if (redirectPolicy == HttpClient.Redirect.NEVER) return null
    val next = prepared.redirectedRequest(response, sent) ?: return null
    if (redirectPolicy == HttpClient.Redirect.NORMAL &&
      sent.uri.scheme == "https" &&
      next.uri.scheme == "http"
    ) {
      return null
    }
    return next
  }

  override fun close() {
    close(true)
  }

  override fun close(cancelOutstandingRequests: Boolean) {
    if (cancelOutstandingRequests) httpClient.shutdownNow() else httpClient.shutdown()
  }

}
