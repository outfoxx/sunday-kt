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
import io.outfoxx.sunday.RequestFactory
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.ContentLength
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.ResultResponse
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
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

  abstract fun createRequestFactory(
    uriTemplate: URITemplate,
    encoders: MediaTypeEncoders = MediaTypeEncoders.default,
    decoders: MediaTypeDecoders = MediaTypeDecoders.default,
  ): RequestFactory

  class API(
    private val requestFactory: RequestFactory,
  ) {

    data class TestResult(
      val message: String,
      val count: Int,
    )

    suspend fun testResult(): TestResult =
      requestFactory.result(
        method = Method.Get,
        pathTemplate = "/test",
        acceptTypes = listOf(JSON),
      )

    suspend fun testResultResponse(): ResultResponse<TestResult> =
      requestFactory.resultResponse(
        method = Method.Get,
        pathTemplate = "/test",
        acceptTypes = listOf(JSON),
      )

    suspend fun testVoidResultResponse(): ResultResponse<Unit> =
      requestFactory.resultResponse(
        method = Method.Get,
        pathTemplate = "/test",
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
          .addHeader(ContentType, JSON)
          .setBody(objectMapper.writeValueAsString(testResult)),
      )
      server.start()
      server.use {
        val api = API(createRequestFactory(URITemplate(server.url("/").toString())))

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
          .addHeader(ContentType, JSON)
          .setBody(objectMapper.writeValueAsString(testResult)),
      )
      server.start()
      server.use {
        val api = API(createRequestFactory(URITemplate(server.url("/").toString())))

        val resultResponse = api.testResultResponse()

        expectThat(resultResponse.result).isEqualTo(testResult)
        expectThat(resultResponse.headers.map { it.first.lowercase() to it.second.lowercase() })
          .contains(ContentType.lowercase() to JSON.value, ContentLength.lowercase() to "29")
      }
    }

  @Test
  fun `generated style API unit result response method`() =
    runTest {
      val server = MockWebServer()
      server.enqueue(
        MockResponse()
          .addHeader(ContentLength, "0")
          .setResponseCode(204),
      )
      server.start()
      server.use {
        val api = API(createRequestFactory(URITemplate(server.url("/").toString())))

        val responseResult = api.testVoidResultResponse()

        expectThat(responseResult.result).isEqualTo(Unit)
        expectThat(responseResult.headers.map { it.first.lowercase() to it.second.lowercase() })
          .contains(ContentLength.lowercase() to "0")
      }
    }

}
