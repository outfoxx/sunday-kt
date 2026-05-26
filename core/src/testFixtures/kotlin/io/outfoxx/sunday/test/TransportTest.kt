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
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.Companion.CBOR
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.MediaType.Companion.HTML
import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.MediaType.Companion.Plain
import io.outfoxx.sunday.MediaType.Companion.Problem
import io.outfoxx.sunday.MediaType.Companion.WWWFormUrlEncoded
import io.outfoxx.sunday.SundayError
import io.outfoxx.sunday.SundayError.Reason.NoSupportedAcceptTypes
import io.outfoxx.sunday.SundayError.Reason.NoSupportedContentTypes
import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames
import io.outfoxx.sunday.http.HeaderNames.CONTENT_TYPE
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.http.getFirst
import io.outfoxx.sunday.mediatypes.codecs.BinaryEncoder
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.TextDecoder
import io.outfoxx.sunday.mediatypes.codecs.URLQueryParamsEncoder
import io.outfoxx.sunday.problems.SundayHttpProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
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

abstract class TransportTest {

  companion object {

    private val objectMapper =
      ObjectMapper()
        .findAndRegisterModules()
  }

  abstract val implementation: Implementation

  abstract fun createTransport(
    uriTemplate: URITemplate,
    encoders: MediaTypeEncoders = MediaTypeEncoders.default,
    decoders: MediaTypeDecoders = MediaTypeDecoders.default,
  ): Transport<Request>

  /**
   * General
   */

  @Test
  fun `allows overriding defaults constructor`() {
    val specialEncoders = MediaTypeEncoders.Builder().build()
    val specialDecoders = MediaTypeDecoders.Builder().build()

    createTransport(URITemplate("http://example.com"), specialEncoders, specialDecoders)
      .use { transport ->

        expectThat(transport.mediaTypeEncoders).isEqualTo(specialEncoders)
        expectThat(transport.mediaTypeDecoders).isEqualTo(specialDecoders)
      }
  }


  /**
   * Request Building
   */

  @Test
  fun `encodes path parameters`() =
    runTest {
      createTransport(URITemplate("http://example.com/{id}"))
        .use { transport ->

          val request =
            transport.transportRequest(
              Method.Get,
              "/encoded-params",
              pathParameters = mapOf("id" to 123),
            )

          expectThat(request.uri)
            .isEqualTo(URI("http://example.com/123/encoded-params"))
        }
    }

  @Test
  fun `encodes query parameters`() =
    runTest {
      createTransport(URITemplate("http://example.com"))
        .use { transport ->

          val request =
            transport.transportRequest(
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
    createTransport(
      URITemplate("http://example.com"),
      encoders =
        MediaTypeEncoders
          .Builder()
          .registerData()
          .registerJSON()
          .build(),
    ).use { transport ->

      expectThrows<SundayError> {
        transport.transportRequest(
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
    createTransport(
      URITemplate("http://example.com"),
      encoders = MediaTypeEncoders.Builder().register(BinaryEncoder(), WWWFormUrlEncoded).build(),
    ).use { transport ->

      expectThrows<SundayError> {
        transport.transportRequest(
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
  fun `adds custom headers`() =
    runTest {
      createTransport(URITemplate("http://example.com"))
        .use { transport ->

          val request =
            transport.transportRequest(
              Method.Get,
              "/add-custom-headers",
              headers = mapOf(HeaderNames.AUTHORIZATION to "Bearer 12345"),
            )

          expectThat(request.headers).contains(HeaderNames.AUTHORIZATION to "Bearer 12345")
        }
    }

  @Test
  fun `adds accept headers`() =
    runTest {
      createTransport(URITemplate("http://example.com"))
        .use { transport ->

          val request =
            transport.transportRequest(
              Method.Get,
              "/add-accept-headers",
              acceptTypes = listOf(JSON, CBOR),
            )

          expectThat(request.headers)
            .contains(HeaderNames.ACCEPT to "application/json , application/cbor")
        }
    }

  @Test
  fun `fails if none of the accept types has a decoder`() {
    createTransport(
      URITemplate("http://example.com"),
      decoders = MediaTypeDecoders.Builder().build(),
    ).use { transport ->

      expectThrows<SundayError> {
        transport.transportRequest(
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
    createTransport(URITemplate("http://example.com"))
      .use { transport ->

        expectThrows<SundayError> {
          transport.transportRequest(
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
  fun `attaches encoded body based on content-type`() =
    runTest {
      createTransport(URITemplate("http://example.com"))
        .use { transport ->

          val request =
            transport.transportRequest(
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
  fun `set content-type when body is non-existent`() =
    runTest {
      createTransport(URITemplate("http://example.com"))
        .use { transport ->

          val request =
            transport.transportRequest(
              Method.Post,
              "/attach-body",
              contentTypes = listOf(JSON),
            )

          expectThat(request.headers).contains(CONTENT_TYPE to "application/json")
        }
    }

  /**
   * Response/Result Building
   */

  @Test
  fun `fetches typed results`() =
    runTest {
      data class Tester(
        val name: String,
        val count: Int,
      )

      val tester = Tester("Test", 10)

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, JSON)
          .setBody(objectMapper.writeValueAsString(tester)),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            val result =
              transport.response<Tester>(
                Method.Get,
                "",
              )

            expectThat(result.headers.getFirst(CONTENT_TYPE)).isEqualTo("application/json")
            expectThat(result.result).isEqualTo(tester)
          }
      }
    }

  @Test
  fun `fetches typed results with body`() =
    runTest {
      data class Tester(
        val name: String,
        val count: Int,
      )

      val tester = Tester("Test", 10)

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, JSON)
          .setBody(objectMapper.writeValueAsString(tester)),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            val result =
              transport.response<Unit, Tester>(
                Method.Get,
                "",
                body = null,
              )

            expectThat(result.headers.getFirst(CONTENT_TYPE)).isEqualTo("application/json")
            expectThat(result.result).isEqualTo(tester)
          }
      }
    }

  @Test
  fun `executes requests with empty responses`() =
    runTest {
      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(204),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            transport.result<Unit>(Method.Post, "")
          }
      }
    }

  @Test
  fun `executes manual requests for responses`() =
    runTest {
      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .setHeader(CONTENT_TYPE, JSON)
          .setBody("[]"),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            val response =
              transport.transportResponse(Method.Get, "")

            expectThat(response.body?.readByteArray()).isEqualTo("[]".encodeToByteArray())
          }
      }
    }

  @Test
  fun `executes manual requests with body for responses`() =
    runTest {
      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .setHeader(CONTENT_TYPE, JSON)
          .setBody("[]"),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            val response =
              transport.transportResponse(Method.Get, "", body = null, contentTypes = listOf(Plain))

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
        .setHeader(CONTENT_TYPE, JSON)
        .setBody("[]"),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          val expectedReasonPhrase: String? = null

          expectThrows<SundayHttpProblem> {
            transport.result<Unit, List<String>>(Method.Get, "", body = null)
          }.and {
            get { status }.isEqualTo(484)
            get { title }.isEqualTo(expectedReasonPhrase)
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
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayError> {
            transport.result<Array<String>>(Method.Get, "")
          }.and {
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
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayError> {
            transport.result<Array<String>>(Method.Get, "")
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
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayError> {
            transport.result<Array<String>>(Method.Get, "")
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
        .setHeader(CONTENT_TYPE, "bad/x-unknown")
        .setBody("some stuff"),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayError> {
            transport.result<Array<String>>(Method.Get, "")
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
        .setHeader(CONTENT_TYPE, "application/x-unknown")
        .setBody("some data"),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayError> {
            transport.result<Array<String>>(Method.Get, "")
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
        .addHeader(CONTENT_TYPE, "application/x-unknown-type")
        .setBody("<test>Test</Test>"),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayError> {
            transport.result<String>(Method.Get, "/problem")
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
        .addHeader(CONTENT_TYPE, JSON)
        .setBody("<test>Test</Test>"),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayError> {
            transport.result<String>(Method.Get, "/problem")
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
        .setResponseCode(TestProblem.STATUS)
        .addHeader(CONTENT_TYPE, Problem)
        .setBody(objectMapper.writeValueAsString(testProblem)),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->
          transport.registerProblem(TestProblem.TYPE, TestProblem::class)

          expectThrows<TestProblem> {
            transport.result<String>(Method.Get, "/problem")
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
        .setResponseCode(TestProblem.STATUS)
        .addHeader(CONTENT_TYPE, Problem)
        .setBody(objectMapper.writeValueAsString(testProblem)),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayHttpProblem> {
            transport.result<String>(Method.Get, "/problem")
          }.and {
            get { type }.isEqualTo(testProblem.type)
            get { title }.isEqualTo(testProblem.title)
            get { status }.isEqualTo(testProblem.status)
            get { detail }.isEqualTo(testProblem.detail)
            get { instance }.isEqualTo(testProblem.instance)
            get { extensions }
              .containsKey("extra")
              .getValue("extra")
              .isEqualTo(testProblem.extra)
          }
        }
    }
  }

  @Test
  fun `test problem responses convert numeric status values`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(400)
        .addHeader(CONTENT_TYPE, Problem)
        .setBody("""{"type":"about:blank","status":499,"title":"Test"}"""),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayHttpProblem> {
            transport.result<String>(Method.Get, "/problem")
          }.and {
            get { status }.isEqualTo(499)
          }
        }
    }
  }

  @Test
  fun `test problem responses convert numeric status strings`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(400)
        .addHeader(CONTENT_TYPE, Problem)
        .setBody("""{"type":"about:blank","status":"404","title":"Test"}"""),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayHttpProblem> {
            transport.result<String>(Method.Get, "/problem")
          }.and {
            get { status }.isEqualTo(404)
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
        .addHeader(CONTENT_TYPE, HTML)
        .setBody("<error>An Error Occurred</error>"),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayHttpProblem> {
            transport.result<String>(Method.Get, "/problem")
          }.and {
            get { type }.isEqualTo(URI("about:blank"))
            get { title }.isEqualTo(Status.BadRequest.reasonPhrase)
            get { status }.isEqualTo(Status.BadRequest.code)
            get { detail }.isNull()
            get { instance }.isNull()
            get { extensions }
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
        .addHeader(CONTENT_TYPE, Problem),
    )
    server.start()
    server.use {
      createTransport(URITemplate(server.url("/").toString()))
        .use { transport ->

          expectThrows<SundayHttpProblem> {
            transport.result<String>(Method.Get, "/problem")
          }.and {
            get { type }.isEqualTo(URI("about:blank"))
            get { title }.isEqualTo(Status.BadRequest.reasonPhrase)
            get { status }.isEqualTo(Status.BadRequest.code)
            get { detail }.isNull()
            get { instance }.isNull()
            get { extensions }.isEmpty()
          }
        }
    }
  }

  @Test
  fun `test problem responses fail with SundayError when no JSON decoder`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(TestProblem.STATUS)
        .addHeader(CONTENT_TYPE, Problem)
        .setBody(objectMapper.writeValueAsString(TestProblem("test"))),
    )
    server.start()
    server.use {
      createTransport(
        URITemplate(server.url("/").toString()),
        decoders = MediaTypeDecoders.Builder().build(),
      ).use { transport ->

        expectThrows<SundayError> {
          transport.result<String>(Method.Get, "/problem")
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
        .setResponseCode(TestProblem.STATUS)
        .addHeader(CONTENT_TYPE, Problem)
        .setBody(objectMapper.writeValueAsString(TestProblem("test"))),
    )
    server.start()
    server.use {
      createTransport(
        URITemplate(server.url("/").toString()),
        decoders = MediaTypeDecoders.Builder().register(TextDecoder.default, JSON).build(),
      ).use { transport ->

        expectThrows<SundayError> {
          transport.result<String>(Method.Get, "/problem")
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
  fun `builds event sources`() =
    runTest {
      val encodedEvent = "event: hello\nid: 12345\ndata: Hello World!\n\n"

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(encodedEvent),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            withContext(Dispatchers.IO) {
              withTimeout(5000) {
                val eventSource = transport.eventSource(Method.Get, "")
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
  fun `builds event sources with explicit body`() =
    runTest {
      val encodedEvent = "event: hello\nid: 12345\ndata: Hello World!\n\n"

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(encodedEvent),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            withContext(Dispatchers.IO) {
              withTimeout(5000) {
                val eventSource = transport.eventSource<Unit>(Method.Get, "", body = null)
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
  fun `builds event streams`() =
    runTest {
      val encodedEvent = "event: hello\nid: 12345\ndata: {\"target\":\"world\"}\n\n"

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(encodedEvent),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            val result =
              withContext(Dispatchers.IO) {
                withTimeout(50000) {
                  val eventStream =
                    transport.eventStream(
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
  fun `event streams reconnect with last-event-id`() =
    runTest {
      val firstEvent = "event: hello\nid: 12345\ndata: {\"target\":\"first\"}\n\n"
      val secondEvent = "event: hello\nid: 67890\ndata: {\"target\":\"second\"}\n\n"

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(firstEvent),
      )
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(secondEvent),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            val result =
              withContext(Dispatchers.IO) {
                withTimeout(5000) {
                  val eventStream =
                    transport.eventStream(
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

                  eventStream.take(2).toList()
                }
              }

            expectThat(result)
              .get { size }
              .isEqualTo(2)

            server.takeRequest()
            val reconnectRequest = server.takeRequest()

            expectThat(reconnectRequest.getHeader(HeaderNames.LAST_EVENT_ID)).isEqualTo("12345")
          }
      }
    }

  @Test
  fun `event streams skip undecodable events`() =
    runTest {
      val encodedEvents =
        "event: hello\nid: 12345\ndata: {\"target\":}\n\n" +
          "event: hello\nid: 67890\ndata: {\"target\":\"world\"}\n\n"

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(encodedEvents),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            val result =
              withContext(Dispatchers.IO) {
                withTimeout(5000) {
                  val eventStream =
                    transport.eventStream(
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
  fun `event streams cancel when decoder cancels`() =
    runTest {
      val encodedEvent = "event: hello\nid: 12345\ndata: {\"target\":\"world\"}\n\n"

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(encodedEvent),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            expectThrows<CancellationException> {
              withContext(Dispatchers.IO) {
                withTimeout(5000) {
                  val eventStream =
                    transport.eventStream(
                      Method.Get,
                      "",
                      decoder = { _, _, _, _, _ ->
                        throw CancellationException("decoder canceled")
                      },
                    )

                  eventStream.first()
                }
              }
            }.and {
              get { message.orEmpty() }.isEqualTo("decoder canceled")
            }
          }
      }
    }

  @Test
  fun `builds event streams with explicit body`() =
    runTest {
      val encodedEvent = "event: hello\nid: 12345\ndata: {\"target\":\"world\"}\n\n"

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(encodedEvent),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            val result =
              withContext(Dispatchers.IO) {
                withTimeout(50000) {
                  val eventStream =
                    transport.eventStream<Unit, Map<String, Any>>(
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

  @Test
  fun `event streams with explicit body reconnect with last-event-id`() =
    runTest {
      val firstEvent = "event: hello\nid: 12345\ndata: {\"target\":\"first\"}\n\n"
      val secondEvent = "event: hello\nid: 67890\ndata: {\"target\":\"second\"}\n\n"

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(firstEvent),
      )
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, EventStream)
          .setBody(secondEvent),
      )
      server.start()
      server.use {
        createTransport(URITemplate(server.url("/").toString()))
          .use { transport ->

            val result =
              withContext(Dispatchers.IO) {
                withTimeout(5000) {
                  val eventStream =
                    transport.eventStream<Map<String, Any>, Map<String, Any>>(
                      Method.Post,
                      "",
                      body = mapOf("filter" to "all"),
                      contentTypes = listOf(JSON),
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

                  eventStream.take(2).toList()
                }
              }

            expectThat(result)
              .get { size }
              .isEqualTo(2)

            server.takeRequest()
            val reconnectRequest = server.takeRequest()

            expectThat(reconnectRequest.getHeader(HeaderNames.LAST_EVENT_ID)).isEqualTo("12345")
          }
      }
    }

  @JsonIgnoreProperties(ignoreUnknown = true)
  data class TestProblem(
    @JsonProperty("extra") val extra: String,
    val instance: URI? = null,
    val type: URI = URI.create(TYPE),
    val title: String? = TITLE,
    val status: Int? = STATUS,
    val detail: String? = DETAIL,
    val parameters: Map<String, Any?> = mapOf("extra" to extra),
  ) : RuntimeException() {

    companion object {
      const val TYPE = "http://example.com/test"
      val STATUS = Status.BadRequest.code
      const val TITLE = "Test Problem"
      const val DETAIL = "A Test Problem"
    }
  }

}
