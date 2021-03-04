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
import io.outfoxx.sunday.MediaType.Companion.ProblemJSON
import io.outfoxx.sunday.NetworkRequestFactory
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.Method
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.zalando.problem.AbstractThrowableProblem

class ProblemDecodingTest {

  @Test
  fun `test specific problem decoding`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(404)
        .addHeader(ContentType, ProblemJSON)
        .setBody(
          """
            {
              "type": "http://example.com/invalid_id",
              "title": "Invalid ID",
              "status": 404,
              "detail": "The ID provided was invalid",
              "instance": "uid:d9e7fd03-e165-4897-81db-c946e330c925",
              "offending_id": "[not_a_good]id"
            }
          """.trimIndent(),
        )
    )
    server.start()

    val httpClient = OkHttpClient.Builder().build()

    val requestFactory =
      NetworkRequestFactory(
        URITemplate(server.url("/test").toString(), mapOf()),
        httpClient,
      )
    requestFactory.registerProblem("http://example.com/invalid_id", InvalidIdProblem::class)

    try {
      runBlocking { requestFactory.result<String>(Method.Get, "") }
      fail { "Expected a Problem to be thrown " }
    } catch (x: Exception) {
      assertThat(x, instanceOf(InvalidIdProblem::class.java))
      assertThat((x as? InvalidIdProblem)?.offendingId, equalTo("[not_a_good]id"))
    }
  }

  @JsonTypeName("http://example.com/invalid_id")
  class InvalidIdProblem(@JsonProperty("offending_id") val offendingId: String) : AbstractThrowableProblem() {

    override fun getCause(): org.zalando.problem.Exceptional? = super.cause
  }
}
