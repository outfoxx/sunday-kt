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

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.Companion.CBOR
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.MediaType.Companion.HTML
import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.MediaType.Companion.ProblemJSON
import io.outfoxx.sunday.MediaType.Companion.WWWFormUrlEncoded
import io.outfoxx.sunday.OkHttpRequestFactory
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.anEmptyMap
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasEntry
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

class OkHttpRequestFactoryTest {

  companion object {

    private val objectMapper =
      ObjectMapper()
        .findAndRegisterModules()
  }

  /**
   * General
   */

  @Test
  fun `allows overriding defaults constructor`() {

    val httpClient = OkHttpClient.Builder().build()

    val specialEncoders = MediaTypeEncoders.Builder().build()
    val specialDecoders = MediaTypeDecoders.Builder().build()

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com"),
        httpClient = httpClient,
        mediaTypeEncoders = specialEncoders,
        mediaTypeDecoders = specialDecoders
      )
    requestFactory.use {

      assertThat(requestFactory.mediaTypeEncoders, equalTo(specialEncoders))
      assertThat(requestFactory.mediaTypeDecoders, equalTo(specialDecoders))
    }
  }


  /**
   * Request Building
   */

  @Test
  fun `encodes query parameters`() {

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com", mapOf()),
        OkHttpClient.Builder().build(),
      )
    requestFactory.use {

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
            headers = null
          )
        }

      assertThat(
        request.url,
        equalTo("http://example.com/encode-query-params?limit=5&search=1%20%26%202".toHttpUrl())
      )
    }
  }

  @Test
  fun `fails when no query parameter encoder is registered and query params are provided`() {

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com", mapOf()),
        OkHttpClient.Builder().build(),
        mediaTypeEncoders = MediaTypeEncoders.Builder().build()
      )
    requestFactory.use {

      val error =
        assertThrows<SundayError> {
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/encode-query-params",
              queryParameters = mapOf("limit" to 5, "search" to "1 & 2")
            )
          }
        }

      assertThat(error.reason, equalTo(SundayError.Reason.NoDecoder))
    }
  }

  @Test
  fun `fails url query parameter encoder is not a URLQueryParamsEncoder`() {

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com", mapOf()),
        OkHttpClient.Builder().build(),
        mediaTypeEncoders = MediaTypeEncoders.Builder().register(BinaryEncoder(), WWWFormUrlEncoded)
          .build()
      )
    requestFactory.use {

      val error =
        assertThrows<SundayError> {
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/encode-query-params",
              queryParameters = mapOf("limit" to 5, "search" to "1 & 2")
            )
          }
        }

      assertThat(error.reason, equalTo(SundayError.Reason.NoDecoder))
      assertThat(error.message, containsString(URLQueryParamsEncoder::class.simpleName))
    }
  }

  @Test
  fun `adds custom headers`() {

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com", mapOf()),
        OkHttpClient.Builder().build(),
      )
    requestFactory.use {

      val request =
        runBlocking {
          requestFactory.request(
            Method.Get,
            "/add-custom-headers",
            headers = mapOf(HeaderNames.Authorization to "Bearer 12345")
          )
        }

      assertThat(request.headers, contains(HeaderNames.Authorization to "Bearer 12345"))
    }
  }

  @Test
  fun `adds accept headers`() {

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com", mapOf()),
        OkHttpClient.Builder().build(),
      )
    requestFactory.use {

      val request =
        runBlocking {
          requestFactory.request(
            Method.Get,
            "/add-accept-headers",
            acceptTypes = listOf(JSON, CBOR)
          )
        }

      assertThat(
        request.headers,
        contains(HeaderNames.Accept to "application/json , application/cbor")
      )
    }
  }

  @Test
  fun `fails if none of the accept types has a decoder`() {

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com", mapOf()),
        OkHttpClient.Builder().build(),
        mediaTypeDecoders = MediaTypeDecoders.Builder().build()
      )
    requestFactory.use {

      val error =
        assertThrows<SundayError> {
          runBlocking {
            requestFactory.request(
              Method.Get,
              "/add-accept-headers",
              acceptTypes = listOf(JSON, CBOR)
            )
          }
        }

      assertThat(error.reason, equalTo(NoSupportedAcceptTypes))
    }
  }

  @Test
  fun `fails if none of the content types has an encoder for the body`() {

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com", mapOf()),
        OkHttpClient.Builder().build(),
      )
    requestFactory.use {

      val error =
        assertThrows<SundayError> {
          runBlocking {
            requestFactory.request(
              Method.Post,
              "/add-accept-headers",
              body = "a body",
              contentTypes = listOf(MediaType.from("application/x-unknown"))
            )
          }
        }

      assertThat(error.reason, equalTo(NoSupportedContentTypes))
    }
  }

  @Test
  fun `attaches encoded body based on content-type`() {

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com", mapOf()),
        OkHttpClient.Builder().build(),
      )
    requestFactory.use {

      val request =
        runBlocking {
          requestFactory.request(
            Method.Post,
            "/attach-body",
            body = mapOf("a" to 5),
            contentTypes = listOf(JSON)
          )
        }

      val buffer = Buffer()
      request.body?.writeTo(buffer)

      assertThat(buffer.readByteArray(), equalTo("""{"a":5}""".encodeToByteArray()))
    }
  }

  @Test
  fun `set content-type when body is non-existent`() {

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate("http://example.com", mapOf()),
        OkHttpClient.Builder().build(),
      )
    requestFactory.use {

      val request =
        runBlocking {
          requestFactory.request(
            Method.Post,
            "/attach-body",
            contentTypes = listOf(JSON)
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
      val count: Int
    )

    val tester = Tester("Test", 10)

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, JSON)
        .setBody(objectMapper.writeValueAsString(tester))
    )
    server.start()
    server.use {

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

        val result =
          runBlocking {
            requestFactory.result<Void, Tester>(
              Method.Get,
              "",
              pathParameters = null,
              queryParameters = null,
              body = null,
              contentTypes = null,
              acceptTypes = null,
              headers = null
            )
          }

        assertThat(result, equalTo(tester))
      }
    }
  }

  @Test
  fun `executes requests with empty responses`() {

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(204)
    )
    server.start()
    server.use {

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

        assertDoesNotThrow {
          runBlocking {
            requestFactory.result(Method.Post, "") as Unit
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
        .setBody("[]")
    )
    server.start()
    server.use {

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

        val response =
          runBlocking {
            requestFactory.response(Method.Get, "")
          }

        assertThat(response.body?.bytes(), equalTo("[]".encodeToByteArray()))
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
        .setBody("[]")
    )
    server.start()
    server.use {

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

        val problem =
          assertThrows<ThrowableProblem> {
            runBlocking {
              requestFactory.result(Method.Get, "") as List<String>
            }
          }

        assertThat(problem.status?.statusCode, equalTo(484))
        assertThat(problem.status?.reasonPhrase, equalTo("Special Status"))
        assertThat(problem.title, equalTo(problem.status?.reasonPhrase))
      }
    }
  }

  @Test
  fun `fails when no data and non empty result types`() {

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(204)
    )
    server.start()
    server.use {

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

        val error =
          assertThrows<SundayError> {
            runBlocking {
              requestFactory.result(Method.Get, "") as Array<String>
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
        .setResponseCode(200)
    )
    server.start()
    server.use {

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

        val error =
          assertThrows<SundayError> {
            runBlocking {
              requestFactory.result(Method.Get, "") as Array<String>
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
        .setBody("some stuff")
    )
    server.start()
    server.use {

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

        val error =
          assertThrows<SundayError> {
            runBlocking {
              requestFactory.result(Method.Get, "") as Array<String>
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
        .setBody("some data")
    )
    server.start()
    server.use {

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

        val error =
          assertThrows<SundayError> {
            runBlocking {
              requestFactory.result(Method.Get, "") as Array<String>
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
        .setBody("<test>Test</Test>")
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          httpClient,
        )
      requestFactory.use {

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
        .setBody("<test>Test</Test>")
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          httpClient,
        )
      requestFactory.use {

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
        .addHeader(ContentType, ProblemJSON)
        .setBody(objectMapper.writeValueAsString(testProblem))
    )
    server.start()
    server.use {

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.registerProblem(TestProblem.TYPE, TestProblem::class)
      requestFactory.use {

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
        .addHeader(ContentType, ProblemJSON)
        .setBody(objectMapper.writeValueAsString(testProblem))
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          httpClient,
        )
      requestFactory.use {

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
        .setBody("<error>An Error Occurred</error>")
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          httpClient,
        )
      requestFactory.use {

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
        .addHeader(ContentType, ProblemJSON)
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          httpClient,
        )
      requestFactory.use {

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
        .addHeader(ContentType, ProblemJSON)
        .setBody(objectMapper.writeValueAsString(TestProblem("test")))
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          httpClient,
          mediaTypeDecoders = MediaTypeDecoders.Builder().build()
        )
      requestFactory.use {

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
        .addHeader(ContentType, ProblemJSON)
        .setBody(objectMapper.writeValueAsString(TestProblem("test")))
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          httpClient,
          mediaTypeDecoders = MediaTypeDecoders.Builder().register(TextDecoder(), JSON).build()
        )
      requestFactory.use {

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

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

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

      val requestFactory =
        OkHttpRequestFactory(
          URITemplate(server.url("/").toString(), mapOf()),
          OkHttpClient.Builder().build(),
        )
      requestFactory.use {

        val result =
          runBlocking {
            withTimeout(50000) {
              val eventStream = requestFactory.eventStream<Map<String, Any>>(
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
                }
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
    instance: URI? = null
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
