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

import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.problems.SundayHttpProblem
import io.outfoxx.sunday.security.BearerCredentials
import io.outfoxx.sunday.security.ClientSettings
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.withSecurity
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClientSettingsTest {
  @Test
  fun `settings adapter uses the prepared manager and endpoint`() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(204))
        val binding =
          SecurityBinding(
            "identity",
            "identity",
            SecurityBinding.Flow.Static,
            transport =
              SecurityBinding.CredentialTransport(
                SecurityBinding.CredentialTransport.Location.Header,
                "Authorization",
                "Bearer",
              ),
          )
        val settings =
          ClientSettings(
            server.url("/v1/").toUri(),
            mapOf("read" to listOf(binding)),
            mapOf("identity" to BearerCredentials("secret")),
          )
        settings.tokenManager!!.use {
          settings.jdkTransport(SundayHttpProblem.Factory).use { transport ->
            assertEquals(0, server.requestCount)
            val request =
              transport
                .withSecurity(
                  settings.bindings.getValue("read"),
                ).transportRequest(Method.Get, "items")
            assertEquals(204, request.execute().statusCode)
            val sent = server.takeRequest()
            assertEquals("/v1/items", sent.path)
            assertEquals("Bearer secret", sent.getHeader("Authorization"))
          }
        }
      }
    }
}
