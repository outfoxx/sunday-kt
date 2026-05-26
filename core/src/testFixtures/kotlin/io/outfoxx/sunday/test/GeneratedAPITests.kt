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

import com.fasterxml.jackson.databind.ObjectMapper
import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.NullableOperation
import io.outfoxx.sunday.NullifySpec
import io.outfoxx.sunday.Operation
import io.outfoxx.sunday.OperationSpec
import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.CONTENT_LENGTH
import io.outfoxx.sunday.http.HeaderNames.CONTENT_TYPE
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.OperationResponse
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.nullableOperation
import io.outfoxx.sunday.operation
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo

abstract class GeneratedAPITests {

  companion object {

    private val objectMapper =
      ObjectMapper()
        .findAndRegisterModules()
  }

  abstract fun createTransport(
    uriTemplate: URITemplate,
    encoders: MediaTypeEncoders = MediaTypeEncoders.default,
    decoders: MediaTypeDecoders = MediaTypeDecoders.default,
  ): Transport<Request>

  class API(
    private val transport: Transport<Request>,
  ) {

    data class TestResult(
      val message: String,
      val count: Int,
    )

    suspend fun testResult(): TestResult =
      transport.result(
        method = Method.Get,
        pathTemplate = "/test",
        acceptTypes = listOf(JSON),
      )

    suspend fun testOperationResponse(): OperationResponse<TestResult> =
      transport.response(
        method = Method.Get,
        pathTemplate = "/test",
        acceptTypes = listOf(JSON),
      )

    suspend fun testVoidOperationResponse(): OperationResponse<Unit> =
      transport.response(
        method = Method.Get,
        pathTemplate = "/test",
      )

    fun testOperation(): Operation<Unit, TestResult, Request> =
      transport.operation(
        OperationSpec(
          method = Method.Get,
          pathTemplate = "/test",
          acceptTypes = listOf(JSON),
        ),
      )

    fun testVoidOperation(): Operation<Unit, Unit, Request> =
      transport.operation(
        OperationSpec(
          method = Method.Get,
          pathTemplate = "/test",
        ),
      )

    fun testNullableOperation(): NullableOperation<Unit, TestResult, Request> =
      transport.nullableOperation(
        OperationSpec(
          method = Method.Get,
          pathTemplate = "/test",
          acceptTypes = listOf(JSON),
        ),
        NullifySpec(statuses = listOf(404)),
      )

  }

  @Test
  fun `generated style API result method`() =
    runTest {
      val testResult = API.TestResult("Test", 10)

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, JSON)
          .setBody(objectMapper.writeValueAsString(testResult)),
      )
      server.start()
      server.use {
        val api = API(createTransport(URITemplate(server.url("/").toString())))

        val result = api.testResult()

        expectThat(result).isEqualTo(testResult)
      }
    }

  @Test
  fun `generated style API result response method`() =
    runTest {
      val testResult = API.TestResult("Test", 10)

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, JSON)
          .setBody(objectMapper.writeValueAsString(testResult)),
      )
      server.start()
      server.use {
        val api = API(createTransport(URITemplate(server.url("/").toString())))

        val response = api.testOperationResponse()

        expectThat(response.result).isEqualTo(testResult)
        expectThat(response.headers.map { it.first.lowercase() to it.second.lowercase() })
          .contains(CONTENT_TYPE.lowercase() to JSON.value, CONTENT_LENGTH.lowercase() to "29")
      }
    }

  @Test
  fun `generated style API unit result response method`() =
    runTest {
      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .addHeader(CONTENT_LENGTH, "0")
          .setResponseCode(204),
      )
      server.start()
      server.use {
        val api = API(createTransport(URITemplate(server.url("/").toString())))

        val responseResult = api.testVoidOperationResponse()

        expectThat(responseResult.result).isEqualTo(Unit)
        expectThat(responseResult.headers.map { it.first.lowercase() to it.second.lowercase() })
          .contains(CONTENT_LENGTH.lowercase() to "0")
      }
    }

  @Test
  fun `generated style API operation execute method`() =
    runTest {
      val testResult = API.TestResult("Test", 10)

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, JSON)
          .setBody(objectMapper.writeValueAsString(testResult)),
      )
      server.start()
      server.use {
        val api = API(createTransport(URITemplate(server.url("/").toString())))

        val result = api.testOperation().execute()

        expectThat(result).isEqualTo(testResult)
      }
    }

  @Test
  fun `generated style API operation request method`() =
    runTest {
      val server = MockWebServer()
      server.start()
      server.use {
        val api = API(createTransport(URITemplate(server.url("/").toString())))

        val request = api.testOperation().transportRequest()

        expectThat(request.uri.toString()).isEqualTo(server.url("/test").toString())
      }
    }

  @Test
  fun `generated style API nullable operation execute or null method returns value`() =
    runTest {
      val testResult = API.TestResult("Test", 10)

      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(200)
          .addHeader(CONTENT_TYPE, JSON)
          .setBody(objectMapper.writeValueAsString(testResult)),
      )
      server.start()
      server.use {
        val api = API(createTransport(URITemplate(server.url("/").toString())))

        val result = api.testNullableOperation().executeOrNull()

        expectThat(result).isEqualTo(testResult)
      }
    }

  @Test
  fun `generated style API nullable operation execute or null method returns null for matching status`() =
    runTest {
      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .setResponseCode(404),
      )
      server.start()
      server.use {
        val api = API(createTransport(URITemplate(server.url("/").toString())))

        val result = api.testNullableOperation().executeOrNull()

        expectThat(result).isEqualTo(null)
      }
    }

  @Test
  fun `generated style API unit operation result response method`() =
    runTest {
      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .addHeader(CONTENT_LENGTH, "0")
          .setResponseCode(204),
      )
      server.start()
      server.use {
        val api = API(createTransport(URITemplate(server.url("/").toString())))

        val responseResult = api.testVoidOperation().response()

        expectThat(responseResult.result).isEqualTo(Unit)
        expectThat(responseResult.headers.map { it.first.lowercase() to it.second.lowercase() })
          .contains(CONTENT_LENGTH.lowercase() to "0")
      }
    }

}
