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

import io.outfoxx.sunday.RequestAdapter
import io.outfoxx.sunday.ResponseAdapter
import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.isRequestCancelled
import io.outfoxx.sunday.problems.SundayHttpProblem
import io.outfoxx.sunday.security.TokenProvider
import io.outfoxx.sunday.test.AdapterExecutionTest
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class JdkAdapterExecutionTest :
  AdapterExecutionTest<java.net.http.HttpRequest, java.net.http.HttpResponse<kotlinx.io.Source>>() {
  override suspend fun executeExport(request: io.outfoxx.sunday.http.Request<java.net.http.HttpRequest>): Int =
    java.net.http.HttpClient.newHttpClient().use { client ->
      client
        .send(
          request.transport,
          java.net.http.HttpResponse.BodyHandlers
            .discarding(),
        ).statusCode()
    }

  override fun transport(
    base: URITemplate,
    requestAdapter: RequestAdapter?,
    responseAdapter: ResponseAdapter?,
    providers: Map<String, TokenProvider>,
  ) = JdkTransport(
    base,
    SundayHttpProblem.Factory,
    requestAdapter = requestAdapter,
    responseAdapter = responseAdapter,
    tokenProviders = providers,
  )

  @Test
  fun `native authentication honors non-repeatable bodies during buffered execution`() =
    checkNativeAuthentication(false)

  @Test
  fun `native authentication honors non-repeatable bodies during streaming execution`() =
    checkNativeAuthentication(true)

  private fun checkNativeAuthentication(streaming: Boolean) =
    runTest {
      for (repeatable in listOf(false, true)) {
        for (challenge in listOf(false, true)) {
          for (length in listOf(null, 7L)) {
            val writes = AtomicInteger()
            val adaptations = AtomicInteger()
            val challenges = AtomicInteger()
            val responses = mutableListOf<Int>()
            MockWebServer().use { server ->
              if (challenge) {
                server.enqueue(
                  MockResponse()
                    .setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Basic realm=probe")
                    .setBody("challenge"),
                )
              }
              server.enqueue(MockResponse().setBody("done"))
              val client =
                java.net.http.HttpClient
                  .newBuilder()
                  .authenticator(
                    object : java.net.Authenticator() {
                      override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                        challenges.incrementAndGet()
                        return java.net.PasswordAuthentication("user", "password".toCharArray())
                      }
                    },
                  ).build()
              JdkTransport(
                URITemplate(server.url("/").toString()),
                SundayHttpProblem.Factory,
                httpClient = client,
                requestAdapter =
                  object : RequestAdapter {
                    override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
                      adaptations.incrementAndGet()
                      val withBody =
                        request.withBody(
                          StreamingBody.source(length) {
                            writes.incrementAndGet()
                            Buffer().apply { writeString("payload") }
                          },
                        )
                      return if (repeatable) withBody else RestrictedRequest(withBody)
                    }
                  },
                responseAdapter =
                  object : ResponseAdapter {
                    override suspend fun <TRes> adapt(
                      response: Response<TRes>,
                      request: Request<*>,
                    ): Response<TRes> {
                      responses += response.statusCode
                      return response
                    }
                  },
              ).use { transport ->
                val result = runCatching { perform(transport, streaming, Method.Post) }
                if (challenge && !repeatable) {
                  assertTrue(result.exceptionOrNull()?.isRequestCancelled == true, result.toString())
                  assertEquals(emptyList<Int>(), responses)
                } else {
                  val response = result.getOrThrow()
                  assertEquals(200, response.statusCode)
                  response.body!!.use { assertEquals("done", it.readString()) }
                  assertEquals(listOf(200), responses)
                }
                assertEquals(if (challenge && repeatable) 2 else 1, writes.get())
                assertEquals(1, adaptations.get())
                assertEquals(if (challenge) 1 else 0, challenges.get())
                assertEquals("payload", server.takeRequest().body.readUtf8())
              }
            }
          }
        }
      }
    }
}
