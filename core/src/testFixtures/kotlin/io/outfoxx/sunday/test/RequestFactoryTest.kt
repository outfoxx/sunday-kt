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

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.Companion.CBOR
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.MediaType.Companion.HTML
import io.outfoxx.sunday.MediaType.Companion.JSON
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
import io.outfoxx.sunday.mediatypes.codecs.BinaryEncoder
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.TextDecoder
import io.outfoxx.sunday.mediatypes.codecs.URLQueryParamsEncoder
import io.outfoxx.sunday.test.Implementation.JDK
import io.outfoxx.sunday.test.Implementation.OkHttp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.anEmptyMap
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasEntry
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.zalando.problem.AbstractThrowableProblem
import org.zalando.problem.DefaultProblem
import org.zalando.problem.Status
import org.zalando.problem.ThrowableProblem
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

        assertThat(requestFactory.mediaTypeEncoders, equalTo(specialEncoders))
        assertThat(requestFactory.mediaTypeDecoders, equalTo(specialDecoders))
      }
  }


  /**
   * Request Building
   */

  @Test
  fun `encodes path parameters`() {
    createRequestFactory(URITemplate("http://example.com/{id}"))
      .use { requestFactory ->

        val request =
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/encoded-params",
              pathParameters = mapOf("id" to 123),
              body = null,
              contentTypes = null,
              acceptTypes = null,
              headers = null,
            )
          }

        assertThat(
          request.uri,
          equalTo(URI("http://example.com/123/encoded-params")),
        )
      }
  }

  @Test
  fun `encodes query parameters`() {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/encode-query-params",
              pathParameters = null,
              queryParameters = mapOf("limit" to 5, "search" to "1 & 2"),
              body = null,
              contentTypes = null,
              acceptTypes = null,
              headers = null,
            )
          }

        assertThat(
          request.uri,
          equalTo(URI("http://example.com/encode-query-params?limit=5&search=1%20%26%202")),
        )
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

      val error =
        assertThrows<SundayError> {
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/encode-query-params",
              queryParameters = mapOf("limit" to 5, "search" to "1 & 2"),
            )
          }
        }

      assertThat(error.reason, equalTo(SundayError.Reason.NoDecoder))
    }
  }

  @Test
  fun `fails url query parameter encoder is not a URLQueryParamsEncoder`() {
    createRequestFactory(
      URITemplate("http://example.com"),
      encoders = MediaTypeEncoders.Builder().register(BinaryEncoder(), WWWFormUrlEncoded).build(),
    ).use { requestFactory ->

      val error =
        assertThrows<SundayError> {
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/encode-query-params",
              queryParameters = mapOf("limit" to 5, "search" to "1 & 2"),
            )
          }
        }

      assertThat(error.reason, equalTo(SundayError.Reason.NoDecoder))
      assertThat(error.message, containsString(URLQueryParamsEncoder::class.simpleName))
    }
  }

  @Test
  fun `adds custom headers`() {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/add-custom-headers",
              headers = mapOf(HeaderNames.Authorization to "Bearer 12345"),
            )
          }

        assertThat(request.headers, contains(HeaderNames.Authorization to "Bearer 12345"))
      }
  }

  @Test
  fun `adds accept headers`() {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/add-accept-headers",
              acceptTypes = listOf(JSON, CBOR),
            )
          }

        assertThat(
          request.headers,
          contains(HeaderNames.Accept to "application/json , application/cbor"),
        )
      }
  }

  @Test
  fun `fails if none of the accept types has a decoder`() {
    createRequestFactory(
      URITemplate("http://example.com"),
      decoders = MediaTypeDecoders.Builder().build(),
    ).use { requestFactory ->

      val error =
        assertThrows<SundayError> {
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/add-accept-headers",
              acceptTypes = listOf(JSON, CBOR),
            )
          }
        }

      assertThat(error.reason, equalTo(NoSupportedAcceptTypes))
    }
  }

  @Test
  fun `fails if none of the content types has an encoder for the body`() {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val error =
          assertThrows<SundayError> {
            runBlocking {
              requestFactory.request(
                Method.Post,
                "/add-accept-headers",
                body = "a body",
                contentTypes = listOf(MediaType.from("application/x-unknown")),
              )
            }
          }

        assertThat(error.reason, equalTo(NoSupportedContentTypes))
      }
  }

  @Test
  fun `attaches encoded body based on content-type`() {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          runBlocking {
            requestFactory.request(
              Method.Post,
              "/attach-body",
              body = mapOf("a" to 5),
              contentTypes = listOf(JSON),
            )
          }

        val body = runBlocking { request.body() }
        assertThat(body?.readByteArray(), equalTo("""{"a":5}""".encodeToByteArray()))
      }
  }

  @Test
  fun `set content-type when body is non-existent`() {
    createRequestFactory(URITemplate("http://example.com"))
      .use { requestFactory ->

        val request =
          runBlocking {
            requestFactory.request(
              Method.Post,
              "/attach-body",
              contentTypes = listOf(JSON),
            )
          }

        assertThat(request.headers, contains(ContentType to "application/json"))
      }
  }

  /**
   * Response/Result Building
   */

  @Test
  fun `fetches typed results`() {
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
            runBlocking {
              requestFactory.resultResponse<Void, Tester>(
                Method.Get,
                "",
                pathParameters = null,
                queryParameters = null,
                body = null,
                contentTypes = null,
                acceptTypes = null,
                headers = null,
              )
            }

          assertThat(result.headers, hasItem(ContentType to "application/json"))
          assertThat(result.result, equalTo(tester))
        }
    }
  }

  @Test
  fun `executes requests with empty responses`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(204),
    )
    server.start()
    server.use {
      createRequestFactory(URITemplate(server.url("/").toString()))
        .use { requestFactory ->

          assertDoesNotThrow {
            runBlocking {
              requestFactory.result<Unit>(Method.Post, "")
            }
          }
        }
    }
  }

  @Test
  fun `executes manual requests for responses`() {
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
            runBlocking {
              requestFactory.response(Method.Get, "")
            }

          assertThat(response.body?.readByteArray(), equalTo("[]".encodeToByteArray()))
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

          val problem =
            assertThrows<ThrowableProblem> {
              runBlocking {
                requestFactory.result<List<String>>(Method.Get, "")
              }
            }

          assertThat(problem.status?.statusCode, equalTo(484))
          assertThat(problem.title, equalTo(problem.status?.reasonPhrase))
          when (implementation) {
            OkHttp -> assertThat(problem.status?.reasonPhrase, equalTo("Special Status"))
            JDK -> assertThat(problem.status?.reasonPhrase, nullValue())
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

          val error =
            assertThrows<SundayError> {
              runBlocking {
                requestFactory.result<Array<String>>(Method.Get, "")
              }
            }

          assertThat(error.reason, equalTo(SundayError.Reason.UnexpectedEmptyResponse))
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

          val error =
            assertThrows<SundayError> {
              runBlocking {
                requestFactory.result<Array<String>>(Method.Get, "")
              }
            }

          assertThat(error.reason, equalTo(SundayError.Reason.NoData))
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

          val error =
            assertThrows<SundayError> {
              runBlocking {
                requestFactory.result<Array<String>>(Method.Get, "")
              }
            }

          assertThat(error.reason, equalTo(SundayError.Reason.InvalidContentType))
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

          val error =
            assertThrows<SundayError> {
              runBlocking {
                requestFactory.result<Array<String>>(Method.Get, "")
              }
            }

          assertThat(error.reason, equalTo(SundayError.Reason.NoDecoder))
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

          val error =
            assertThrows<SundayError> {
              runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
            }

          assertThat(error.reason, equalTo(SundayError.Reason.NoDecoder))
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

          val error =
            assertThrows<SundayError> {
              runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
            }

          assertThat(error.reason, equalTo(SundayError.Reason.ResponseDecodingFailed))
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

          val error =
            assertThrows<TestProblem> {
              runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
            }

          assertThat(error.type, equalTo(testProblem.type))
          assertThat(error.title, equalTo(testProblem.title))
          assertThat(error.status, equalTo(testProblem.status))
          assertThat(error.detail, equalTo(testProblem.detail))
          assertThat(error.instance, equalTo(testProblem.instance))
          assertThat(error.parameters, equalTo(testProblem.parameters))
          assertThat(error.extra, equalTo(testProblem.extra))
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

          val error =
            assertThrows<DefaultProblem> {
              runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
            }

          assertThat(error.type, equalTo(testProblem.type))
          assertThat(error.title, equalTo(testProblem.title))
          assertThat(error.status, equalTo(testProblem.status))
          assertThat(error.detail, equalTo(testProblem.detail))
          assertThat(error.instance, equalTo(testProblem.instance))
          assertThat(error.parameters, hasEntry("extra", testProblem.extra))
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

          val error =
            assertThrows<DefaultProblem> {
              runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
            }

          assertThat(error.type, equalTo(URI("about:blank")))
          assertThat(error.title, equalTo("Bad Request"))
          assertThat(error.status, equalTo(Status.BAD_REQUEST))
          assertThat(error.detail, nullValue())
          assertThat(error.instance, nullValue())
          assertThat(error.parameters, hasEntry("responseText", "<error>An Error Occurred</error>"))
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

          val error =
            assertThrows<DefaultProblem> {
              runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
            }

          assertThat(error.type, equalTo(URI("about:blank")))
          assertThat(error.title, equalTo("Bad Request"))
          assertThat(error.status, equalTo(Status.BAD_REQUEST))
          assertThat(error.detail, nullValue())
          assertThat(error.instance, nullValue())
          assertThat(error.parameters, anEmptyMap())
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

        val error =
          assertThrows<SundayError> {
            runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
          }

        assertThat(error.reason, equalTo(SundayError.Reason.NoDecoder))
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

        val error =
          assertThrows<SundayError> {
            runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
          }

        assertThat(error.reason, equalTo(SundayError.Reason.NoDecoder))
      }
    }
  }


  /**
   * Event Source/Stream Building
   */

  @Test
  fun `builds event sources`() {
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

          runBlocking {
            withTimeout(5000) {
              val eventSource = requestFactory.eventSource(Method.Get, "")
              eventSource.use {
                suspendCancellableCoroutine<Unit> { continuation ->
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
  fun `builds event streams`() {
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
            runBlocking {
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

          assertThat(result, hasEntry("target", "world"))
        }
    }
  }

  class TestProblem(
    @JsonProperty("extra") val extra: String,
    instance: URI? = null,
  ) : AbstractThrowableProblem(
      URI.create(TYPE),
      TITLE,
      STATUS,
      DETAIL,
      instance,
    ) {

    companion object {

      const val TYPE = "http://example.com/test"
      val STATUS = Status.BAD_REQUEST
      const val TITLE = "Test Problem"
      const val DETAIL = "A Test Problem"
    }

    override fun getCause(): org.zalando.problem.Exceptional? = super.cause
  }

}
