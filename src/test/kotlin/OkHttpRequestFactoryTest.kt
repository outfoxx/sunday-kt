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
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import io.outfoxx.sunday.MediaType.Companion.ProblemJSON
import io.outfoxx.sunday.OkHttpRequestFactory
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.Method
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasEntry
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.zalando.problem.AbstractThrowableProblem
import org.zalando.problem.DefaultProblem
import org.zalando.problem.Problem
import org.zalando.problem.Status
import java.net.URI

class OkHttpRequestFactoryTest {

  companion object {
    val objectMapper =
      ObjectMapper()
        .findAndRegisterModules()
  }

  class TestProblem(
    @JsonProperty("extra") val extra: String, instance: URI? = null
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
      val TITLE = "Test Problem"
      val DETAIL = "A Test Problem"
    }

    override fun getCause(): org.zalando.problem.Exceptional? = super.cause
  }

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

    val httpClient = OkHttpClient.Builder().build()

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate(server.url("/").toString(), mapOf()),
        httpClient,
      )
    requestFactory.registerProblem(TestProblem.TYPE, TestProblem::class)

    val error =
      try {
        runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
        fail { "Expected a Problem to be thrown " }
      } catch (x: Exception) {
        x
      }

    assertThat(error, instanceOf(TestProblem::class.java))
    error as TestProblem
    assertThat(error.type, equalTo(testProblem.type))
    assertThat(error.title, equalTo(testProblem.title))
    assertThat(error.status, equalTo(testProblem.status))
    assertThat(error.detail, equalTo(testProblem.detail))
    assertThat(error.instance, equalTo(testProblem.instance))
    assertThat(error.parameters, equalTo(testProblem.parameters))
    assertThat(error.extra, equalTo(testProblem.extra))
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

    val httpClient = OkHttpClient.Builder().build()

    val requestFactory =
      OkHttpRequestFactory(
        URITemplate(server.url("/").toString(), mapOf()),
        httpClient,
      )

    val error =
      try {
        runBlocking { requestFactory.result<String>(Method.Get, "/problem") }
        fail { "Expected a Problem to be thrown " }
      } catch (x: Exception) {
        x
      }

    assertThat(error, instanceOf(DefaultProblem::class.java))
    error as Problem
    assertThat(error.type, equalTo(testProblem.type))
    assertThat(error.title, equalTo(testProblem.title))
    assertThat(error.status, equalTo(testProblem.status))
    assertThat(error.detail, equalTo(testProblem.detail))
    assertThat(error.instance, equalTo(testProblem.instance))
    assertThat(error.parameters, hasEntry("extra", testProblem.extra))
  }

}
