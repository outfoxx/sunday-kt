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
import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.problems.SundayHttpProblem
import io.outfoxx.sunday.security.AuthenticationAdapter
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.TokenConfiguration
import io.outfoxx.sunday.security.TokenProvider
import io.outfoxx.sunday.security.TokenRequest
import io.outfoxx.sunday.security.TokenSet
import io.outfoxx.sunday.test.NativeRequestTypesTest
import io.outfoxx.sunday.withSecurity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JdkNativeRequestTypesTest :
  NativeRequestTypesTest<java.net.http.HttpRequest, java.net.http.HttpResponse<kotlinx.io.Source>>() {
  override fun transport(
    base: URITemplate,
    adapter: RequestAdapter,
  ) = JdkTransport(
    base,
    SundayHttpProblem.Factory,
    requestAdapter = adapter,
    adapter = { request ->
      java.net.http.HttpRequest
        .newBuilder(request) { _, _ -> true }
        .setHeader("X-Legacy", "yes")
        .timeout(java.time.Duration.ofSeconds(17))
        .build()
    },
  )

  override fun assertNativeResponse(response: java.net.http.HttpResponse<kotlinx.io.Source>) {
    assertEquals(200, response.statusCode())
  }

  override fun assertNative(request: java.net.http.HttpRequest) {
    assertEquals("yes", request.headers().firstValue("X-Decorated").orElse(null))
    assertEquals(listOf("yes"), request.headers().allValues("X-Legacy"))
    assertEquals(java.time.Duration.ofSeconds(17), request.timeout().orElse(null))
  }

  @Test
  fun `native adapter precedes portable authentication and adaptation in every execution mode`() =
    runTest {
      for (mode in listOf("export", "buffered", "streaming")) {
        val calls = mutableListOf<String>()
        val provider =
          object : TokenProvider {
            override val identity = "external"

            override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

            override suspend fun acquire(request: TokenRequest): TokenSet {
              calls += "acquire"
              return TokenSet("token")
            }
          }
        val binding =
          SecurityBinding(
            "external",
            "external",
            transport =
              SecurityBinding.CredentialTransport(
                SecurityBinding.CredentialTransport.Location.Header,
                "Authorization",
                "Bearer",
              ),
          )
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setBody("body"))
          val authentication = AuthenticationAdapter(mapOf("external" to provider))
          JdkTransport(
            URITemplate(server.url("/").toString()),
            SundayHttpProblem.Factory,
            responseAdapter = authentication,
            adapter = { request ->
              assertEquals(null, request.headers().firstValue("Authorization").orElse(null))
              calls += "native"
              java.net.http.HttpRequest
                .newBuilder(request) { _, _ -> true }
                .header("X-Native", "yes")
                .build()
            },
            requestAdapter =
              authentication.chainTo(
                object : RequestAdapter {
                  override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
                    assertEquals("yes", request.headers.first { it.first.equals("X-Native", true) }.second)
                    calls += "portable"
                    return request
                  }
                },
              ),
          ).withSecurity(listOf(binding)).use { transport ->
            val request =
              transport.transportRequest<Unit>(
                Method.Get,
                "value",
                purpose =
                  if (mode ==
                    "export"
                  ) {
                    Transport.RequestPurpose.Export
                  } else {
                    Transport.RequestPurpose.Normal
                  },
              )
            when (mode) {
              "export" ->
                assertEquals(
                  "Bearer token",
                  request.transport
                    .headers()
                    .firstValue("Authorization")
                    .orElse(null),
                )
              "buffered" -> transport.transportResponse(request).body?.close()
              "streaming" -> transport.start(request).toList()
            }
            assertEquals(listOf("native", "acquire", "portable"), calls, mode)
            assertEquals(if (mode == "export") 0 else 1, server.requestCount, mode)
          }
        }
      }
    }

}
