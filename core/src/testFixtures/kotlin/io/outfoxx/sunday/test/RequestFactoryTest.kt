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

package io.outfoxx.sunday.test

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.Companion.CBOR
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.MediaType.Companion.HTML
import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.MediaType.Companion.Plain
import io.outfoxx.sunday.MediaType.Companion.Problem
import io.outfoxx.sunday.MediaType.Companion.WWWFormUrlEncoded
import io.outfoxx.sunday.RequestFactory
import io.outfoxx.sunday.SundayError
import io.outfoxx.sunday.SundayError.Reason.NoSupportedAcceptTypes
import io.outfoxx.sunday.SundayError.Reason.NoSupportedContentTypes
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.getFirst
import io.outfoxx.sunday.mediatypes.codecs.BinaryEncoder
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.TextDecoder
import io.outfoxx.sunday.mediatypes.codecs.URLQueryParamsEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.zalando.problem.AbstractThrowableProblem
import org.zalando.problem.Exceptional
import org.zalando.problem.Status
import org.zalando.problem.ThrowableProblem
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.contains
import strikt.assertions.containsKey
import strikt.assertions.getValue
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import java.net.URI
import kotlin.coroutines.resume
import kotlin.reflect.typeOf

abstract class RequestFactoryTest {

  companion object {

    private val objectMapper =
      ObjectMapper()
        .findAndRegisterModules()
  }

  abstract val implementation: Implementation

  abstract fun createRequestFactory(
    uriTemplate: URITemplate,
    encoders: MediaTypeEncoders = MediaTypeEncoders.default,
    decoders: MediaTypeDecoders = MediaTypeDecoders.default,
  ): RequestFactory

  /**
   * General
   */

  @Test
  fun `allows overriding defaults constructor`() {
    val specialEncoders = MediaTypeEncoders.Builder().build()
    val specialDecoders = MediaTypeDecoders.Builder().build()

    createRequestFactory(URITemplate("http://example.com"), specialEncoders, specialDecoders)
      .use { requestFactory ->

        expectThat(requestFactory.mediaTypeEncoders).isEqualTo(specialEncoders)
        expectThat(requestFactory.mediaTypeDecoders).isEqualTo(specialDecoders)
      }
  }


  /**
   * Request Building
   */

  @Test
  fun `encodes path parameters`() = runTest {
    createRequestFactory(URITemplate("http://example.com/{id}"))
      .use { requestFactory ->

        val request =
          requestFactory.request(
            Method.Get,
            "/encoded-params",
            pathParameters = mapOf("id" to 123),
          )

        expectThat(request.uri)
          .isEqualTo(URI("http://example.com/123/encoded-params"))
      }
  }

  @Test
  fun `encodes query parameters`() = runTest {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          requestFactory.request(
            Method.Get,
            "/encode-query-params",
            queryParameters = mapOf("limit" to 5, "search" to "1 & 2"),
          )

        expectThat(request.uri)
          .isEqualTo(URI("http://example.com/encode-query-params?limit=5&search=1%20%26%202"))
      }
  }

  @Test
  fun `fails when no query parameter encoder is registered and query params are provided`() {
    createRequestFactory(
      URITemplate("http://example.com"),
      encoders =
        MediaTypeEncoders
          .Builder()
          .registerData()
          .registerJSON()
          .build(),
    ).use { requestFactory ->

      expectThrows<SundayError> {
        requestFactory.request(
          Method.Get,
          "/encode-query-params",
          queryParameters = mapOf("limit" to 5, "search" to "1 & 2"),
        )
      }.and {
        get { reason }.isEqualTo(SundayError.Reason.NoDecoder)
      }
    }
  }

  @Test
  fun `fails url query parameter encoder is not a URLQueryParamsEncoder`() {
    createRequestFactory(
      URITemplate("http://example.com"),
      encoders = MediaTypeEncoders.Builder().register(BinaryEncoder(), WWWFormUrlEncoded).build(),
    ).use { requestFactory ->

      expectThrows<SundayError> {
        requestFactory.request(
          Method.Get,
          "/encode-query-params",
          queryParameters = mapOf("limit" to 5, "search" to "1 & 2"),
        )
      }.and {
        get { reason }.isEqualTo(SundayError.Reason.NoDecoder)
        get { message.orEmpty() }.contains(URLQueryParamsEncoder::class.simpleName ?: "")
      }
    }
  }

  @Test
  fun `adds custom headers`() = runTest {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          requestFactory.request(
            Method.Get,
            "/add-custom-headers",
            headers = mapOf(HeaderNames.Authorization to "Bearer 12345"),
          )

        expectThat(request.headers).contains(HeaderNames.Authorization to "Bearer 12345")
      }
  }

  @Test
  fun `adds accept headers`() = runTest {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          requestFactory.request(
            Method.Get,
            "/add-accept-headers",
            acceptTypes = listOf(JSON, CBOR),
          )

        expectThat(request.headers)
          .contains(HeaderNames.Accept to "application/json , application/cbor")
      }
  }

  @Test
  fun `fails if none of the accept types has a decoder`() {
    createRequestFactory(
      URITemplate("http://example.com"),
      decoders = MediaTypeDecoders.Builder().build(),
    ).use { requestFactory ->

      expectThrows<SundayError> {
        requestFactory.request(
          Method.Get,
          "/add-accept-headers",
          acceptTypes = listOf(JSON, CBOR),
        )
      }.and {
        get { reason }.isEqualTo(NoSupportedAcceptTypes)
      }
    }
  }

  @Test
  fun `fails if none of the content types has an encoder for the body`() {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        expectThrows<SundayError> {
          requestFactory.request(
            Method.Post,
            "/add-accept-headers",
            body = "a body",
            contentTypes = listOf(MediaType.from("application/x-unknown")),
          )
        }.and {
          get { reason }.isEqualTo(NoSupportedContentTypes)
        }
      }
  }

  @Test
  fun `attaches encoded body based on content-type`() = runTest {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          requestFactory.request(
            Method.Post,
            "/attach-body",
            body = mapOf("a" to 5),
            contentTypes = listOf(JSON),
          )

        val body = request.body()
        expectThat(body?.readByteArray()).isEqualTo("""{"a":5}""".encodeToByteArray())
      }
  }

  @Test
  fun `set content-type when body is non-existent`() = runTest {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          requestFactory.request(
            Method.Post,
            "/attach-body",
            contentTypes = listOf(JSON),
          )

        expectThat(request.headers).contains(ContentType to "application/json")
      }
  }

  /**
   * Response/Result Building
   */

  @Test
  fun `fetches typed results`() = runTest {
    data class Tester(
      val name: String,
      val count: Int,
    )

    val tester = Tester("Test", 10)

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, JSON)
        .setBody(objectMapper.writeValueAsString(tester)),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          val result =
            requestFactory.resultResponse<Tester>(
              Method.Get,
              "",
            )

          expectThat(result.headers.getFirst(ContentType)).isEqualTo("application/json")
          expectThat(result.result).isEqualTo(tester)
        }
    }
  }

  @Test
  fun `fetches typed results with body`() = runTest {
    data class Tester(
      val name: String,
      val count: Int,
    )

    val tester = Tester("Test", 10)

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, JSON)
        .setBody(objectMapper.writeValueAsString(tester)),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          val result =
            requestFactory.resultResponse<Unit, Tester>(
              Method.Get,
              "",
              body = null,
            )

          expectThat(result.headers.getFirst(ContentType)).isEqualTo("application/json")
          expectThat(result.result).isEqualTo(tester)
        }
    }
  }

  @Test
  fun `executes requests with empty responses`() = runTest {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(204),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          requestFactory.result<Unit>(Method.Post, "")
        }
    }
  }

  @Test
  fun `executes manual requests for responses`() = runTest {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader(ContentType, JSON)
        .setBody("[]"),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          val response =
            requestFactory.response(Method.Get, "")

          expectThat(response.body?.readByteArray()).isEqualTo("[]".encodeToByteArray())
        }
    }
  }

  @Test
  fun `executes manual requests with body for responses`() = runTest {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader(ContentType, JSON)
        .setBody("[]"),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          val response =
            requestFactory.response(Method.Get, "", body = null, contentTypes = listOf(Plain))

          expectThat(response.body?.readByteArray()).isEqualTo("[]".encodeToByteArray())
        }
    }
  }

  @Test
  fun `error responses with non standard status codes are handled`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setStatus("HTTP/1.1 484 Special Status")
        .setHeader(ContentType, JSON)
        .setBody("[]"),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          val expectedReasonPhrase: String? = null

          expectThrows<ThrowableProblem> {
            requestFactory.result<Unit, List<String>>(Method.Get, "", body = null)
          }.and {
            get { status?.statusCode }.isEqualTo(484)
            get { status?.reasonPhrase }.isEqualTo(expectedReasonPhrase)
            get { title }.isNull()
          }
        }
    }
  }

  @Test
  fun `fails when no data and non empty result types`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(204),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<SundayError> { requestFactory.result<Array<String>>(Method.Get, "") }
            .and {
              get { reason }.isEqualTo(SundayError.Reason.UnexpectedEmptyResponse)
            }
        }
    }
  }

  @Test
  fun `fails when a result is expected and no data is returned in response`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<SundayError> {
            requestFactory.result<Array<String>>(Method.Get, "")
          }.and {
            get { reason }.isEqualTo(SundayError.Reason.NoData)
          }
        }
    }
  }

  @Test
  fun `fails when response content-type is missing`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("some stuff"),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<SundayError> {
            requestFactory.result<Array<String>>(Method.Get, "")
          }.and {
            get { reason }.isEqualTo(SundayError.Reason.InvalidContentType)
            get { message.orEmpty() }.contains("<none provided>")
          }
        }
    }
  }

  @Test
  fun `fails when response content-type is invalid`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader(ContentType, "bad/x-unknown")
        .setBody("some stuff"),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<SundayError> {
            requestFactory.result<Array<String>>(Method.Get, "")
          }.and {
            get { reason }.isEqualTo(SundayError.Reason.InvalidContentType)
            get { message.orEmpty() }.contains("bad/x-unknown")
          }
        }
    }
  }

  @Test
  fun `fails when response content-type is unsupported`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader(ContentType, "application/x-unknown")
        .setBody("some data"),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<SundayError> {
            requestFactory.result<Array<String>>(Method.Get, "")
          }.and {
            get { reason }.isEqualTo(SundayError.Reason.NoDecoder)
          }
        }
    }
  }

  @Test
  fun `test decoding fails when no decoder for content-type`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, "application/x-unknown-type")
        .setBody("<test>Test</Test>"),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<SundayError> {
            requestFactory.result<String>(Method.Get, "/problem")
          }.and {
            get { reason }.isEqualTo(SundayError.Reason.NoDecoder)
          }
        }
    }
  }

  @Test
  fun `test decoding errors are translated to SundayError`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, JSON)
        .setBody("<test>Test</Test>"),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<SundayError> {
            requestFactory.result<String>(Method.Get, "/problem")
          }.and {
            get { reason }.isEqualTo(SundayError.Reason.ResponseDecodingFailed)
          }
        }
    }
  }

  /**
   * Problem Building/Handling
   */

  @Test
  fun `test registered problems decode as typed problems`() {
    val testProblem = TestProblem("Some Extra", URI.create("id:12345"))

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(TestProblem.STATUS.statusCode)
        .addHeader(ContentType, Problem)
        .setBody(objectMapper.writeValueAsString(testProblem)),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->
          requestFactory.registerProblem(TestProblem.TYPE, TestProblem::class)

          expectThrows<TestProblem> {
            requestFactory.result<String>(Method.Get, "/problem")
          }.and {
            get { type }.isEqualTo(testProblem.type)
            get { title }.isEqualTo(testProblem.title)
            get { status }.isEqualTo(testProblem.status)
            get { detail }.isEqualTo(testProblem.detail)
            get { instance }.isEqualTo(testProblem.instance)
            get { parameters }.isEqualTo(testProblem.parameters)
            get { extra }.isEqualTo(testProblem.extra)
          }
        }
    }
  }

  @Test
  fun `test unregistered problems decode as generic problems`() {
    val testProblem = TestProblem("Some Extra", URI.create("id:12345"))

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(TestProblem.STATUS.statusCode)
        .addHeader(ContentType, Problem)
        .setBody(objectMapper.writeValueAsString(testProblem)),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<ThrowableProblem> {
            requestFactory.result<String>(Method.Get, "/problem")
          }.and {
            get { type }.isEqualTo(testProblem.type)
            get { title }.isEqualTo(testProblem.title)
            get { status }.isEqualTo(testProblem.status)
            get { detail }.isEqualTo(testProblem.detail)
            get { instance }.isEqualTo(testProblem.instance)
            get { parameters }
              .containsKey("extra")
              .getValue("extra")
              .isEqualTo(testProblem.extra)
          }
        }
    }
  }

  @Test
  fun `test non problem error responses are translated to predefined problems`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(400)
        .addHeader(ContentType, HTML)
        .setBody("<error>An Error Occurred</error>"),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<ThrowableProblem> {
            requestFactory.result<String>(Method.Get, "/problem")
          }.and {
            get { type }.isEqualTo(URI("about:blank"))
            get { title }.isNull()
            get { status }.isEqualTo(Status.BAD_REQUEST)
            get { detail }.isNull()
            get { instance }.isNull()
            get { parameters }
              .containsKey("responseText")
              .getValue("responseText")
              .isEqualTo("<error>An Error Occurred</error>")
          }
        }
    }
  }

  @Test
  fun `test problem responses with empty bodies are translated to predefined problems`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(400)
        .addHeader(ContentType, Problem),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          expectThrows<ThrowableProblem> {
            requestFactory.result<String>(Method.Get, "/problem")
          }.and {
            get { type }.isEqualTo(URI("about:blank"))
            get { title }.isNull()
            get { status }.isEqualTo(Status.BAD_REQUEST)
            get { detail }.isNull()
            get { instance }.isNull()
            get { parameters }.isEmpty()
          }
        }
    }
  }

  @Test
  fun `test problem responses fail with SundayError when no JSON decoder`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(TestProblem.STATUS.statusCode)
        .addHeader(ContentType, Problem)
        .setBody(objectMapper.writeValueAsString(TestProblem("test"))),
    )
    server.start()
    server.use {
      createRequestFactory(
        URITemplate(server.url("/").toString()),
        decoders = MediaTypeDecoders.Builder().build(),
      ).use { requestFactory ->

        expectThrows<SundayError> {
          requestFactory.result<String>(Method.Get, "/problem")
        }.and {
          get { reason }.isEqualTo(SundayError.Reason.NoDecoder)
        }
      }
    }
  }

  @Test
  fun `test problem responses fail when registered JSON decoder is not a structured decoder`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(TestProblem.STATUS.statusCode)
        .addHeader(ContentType, Problem)
        .setBody(objectMapper.writeValueAsString(TestProblem("test"))),
    )
    server.start()
    server.use {
      createRequestFactory(
        URITemplate(server.url("/").toString()),
        decoders = MediaTypeDecoders.Builder().register(TextDecoder.default, JSON).build(),
      ).use { requestFactory ->

        expectThrows<SundayError> {
          requestFactory.result<String>(Method.Get, "/problem")
        }.and {
          get { reason }.isEqualTo(SundayError.Reason.NoDecoder)
        }
      }
    }
  }


  /**
   * Event Source/Stream Building
   */

  @Test
  fun `builds event sources`() = runTest {
    val encodedEvent = "event: hello\nid: 12345\ndata: Hello World!\n\n"

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setBody(encodedEvent),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          withContext(Dispatchers.IO) {
            withTimeout(5000) {
              val eventSource = requestFactory.eventSource(Method.Get, "")
              eventSource.use {
                suspendCancellableCoroutine { continuation ->
                  eventSource.onMessage = { _ ->
                    continuation.resume(Unit)
                  }
                  eventSource.connect()
                }

              }
            }
          }
        }
    }
  }

  @Test
  fun `builds event sources with explicit body`() = runTest {
    val encodedEvent = "event: hello\nid: 12345\ndata: Hello World!\n\n"

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setBody(encodedEvent),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          withContext(Dispatchers.IO) {
            withTimeout(5000) {
              val eventSource = requestFactory.eventSource<Unit>(Method.Get, "", body = null)
              eventSource.use {
                suspendCancellableCoroutine { continuation ->
                  eventSource.onMessage = { _ ->
                    continuation.resume(Unit)
                  }
                  eventSource.connect()
                }

              }
            }
          }
        }
    }
  }

  @Test
  fun `builds event streams`() = runTest {
    val encodedEvent = "event: hello\nid: 12345\ndata: {\"target\":\"world\"}\n\n"

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setBody(encodedEvent),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          val result =
            withContext(Dispatchers.IO) {
              withTimeout(50000) {
                val eventStream =
                  requestFactory.eventStream(
                    Method.Get,
                    "",
                    decoder = { decoder, event, _, data, logger ->
                      when (event) {
                        "hello" -> decoder.decode<Map<String, Any>>(data, typeOf<Map<String, Any>>())
                        else -> {
                          logger.error("unsupported event type")
                          null
                        }
                      }
                    },
                  )

                eventStream.first()
              }
            }

          expectThat(result)
            .containsKey("target")
            .getValue("target")
            .isEqualTo("world")
        }
    }
  }

  @Test
  fun `builds event streams with explicit body`() = runTest {
    val encodedEvent = "event: hello\nid: 12345\ndata: {\"target\":\"world\"}\n\n"

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setBody(encodedEvent),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          val result =
            withContext(Dispatchers.IO) {
              withTimeout(50000) {
                val eventStream =
                  requestFactory.eventStream<Unit, Map<String, Any>>(
                    Method.Get,
                    "",
                    body = null,
                    decoder = { decoder, event, _, data, logger ->
                      when (event) {
                        "hello" -> decoder.decode(data, typeOf<Map<String, Any>>())
                        else -> {
                          logger.error("unsupported event type")
                          null
                        }
                      }
                    },
                  )

                eventStream.first()
              }
            }

          expectThat(result)
            .containsKey("target")
            .getValue("target")
            .isEqualTo("world")
        }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  class TestProblem(
    extra: String,
    instance: URI? = null,
    type: URI = URI.create(TYPE),
    title: String? = TITLE,
    status: Status? = STATUS,
    detail: String? = DETAIL,
    parameters: Map<String, Any?> = mapOf("extra" to extra),
  ) : AbstractThrowableProblem(type, title, status, detail, instance, null, parameters) {

    override fun getCause(): Exceptional? = null

    var extra: String by this.parameters

    companion object {

      const val TYPE = "http://example.com/test"
      val STATUS = Status.BAD_REQUEST
      const val TITLE = "Test Problem"
      const val DETAIL = "A Test Problem"
    }
  }

}
