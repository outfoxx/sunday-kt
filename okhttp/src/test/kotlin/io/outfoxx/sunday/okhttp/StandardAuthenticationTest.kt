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

package io.outfoxx.sunday.okhttp

import io.outfoxx.sunday.RequestAdapter
import io.outfoxx.sunday.RequestCancellationException
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.causes
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.copy
import io.outfoxx.sunday.isRequestCancelled
import io.outfoxx.sunday.problems.SundayHttpProblem
import io.outfoxx.sunday.security.ClientSettings
import io.outfoxx.sunday.security.ProviderCredentials
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.StandardAuthenticationAdapter
import io.outfoxx.sunday.security.TokenConfiguration
import io.outfoxx.sunday.security.TokenManager
import io.outfoxx.sunday.security.TokenProvider
import io.outfoxx.sunday.security.TokenProviderException
import io.outfoxx.sunday.security.TokenRejectedException
import io.outfoxx.sunday.security.TokenRequest
import io.outfoxx.sunday.security.TokenSet
import io.outfoxx.sunday.withSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class StandardAuthenticationTest {
  private val binding =
    SecurityBinding(
      "identity",
      "identity",
      SecurityBinding.Flow.ClientCredentials,
      transport =
        SecurityBinding.CredentialTransport(
          SecurityBinding.CredentialTransport.Location.Header,
          "Authorization",
          "Bearer",
        ),
    )

  private class Time : Clock() {
    var current = Instant.now()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
  }

  @Test
  fun `generated settings use explicit caching expiry and terminal rejection feedback`() =
    runTest {
      val clock = Time()
      var acquisitions = 0
      var refreshes = 0
      val provider =
        object : TokenProvider.Refreshing {
          override val identity = "fixture"

          override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

          override suspend fun acquire(request: TokenRequest): TokenSet {
            acquisitions++
            return TokenSet("initial", expiresAt = clock.instant().plusSeconds(100), refreshToken = "r1")
          }

          override suspend fun refresh(
            request: TokenRequest,
            refreshToken: String,
          ): TokenSet {
            refreshes++
            return TokenSet("renewed-$refreshes", expiresAt = clock.instant().plusSeconds(100), refreshToken = "r2")
          }
        }
      TokenManager(provider = provider, clock = clock, expirySkew = Duration.ZERO, scope = this).use { manager ->
        MockWebServer().use { server ->
          repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
          server.enqueue(
            MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"),
          )
          server.enqueue(MockResponse().setResponseCode(204))
          val settings =
            ClientSettings.resolve(
              server.url("/").toUri(),
              mapOf("read" to listOf(listOf(binding))),
              mapOf("identity" to ProviderCredentials(manager)),
            )
          assertSame(manager, settings.tokenProviders["identity"])
          settings.okhttpTransport(SundayHttpProblem.Factory).use { transport ->
            assertEquals(0, acquisitions)
            val generated = transport.withSecurity(settings.bindings.getValue("read"))
            repeat(2) { generated.transportResponse(Method.Get, "value").body?.close() }
            clock.current = clock.current.plusSeconds(101)
            generated.transportResponse(Method.Get, "value").body?.close()
            assertEquals(1, acquisitions)
            assertEquals(1, refreshes)
            assertThrows<TokenRejectedException> { generated.transportResponse(Method.Get, "value") }
            assertEquals(4, server.requestCount)
            generated.transportResponse(Method.Get, "value").body?.close()
            assertEquals(2, refreshes)
          }
        }
      }
    }

  @Test
  fun `same generated operation works with custom and plain transports while missing policy is explicit`() =
    runTest {
      var calls = 0
      val custom =
        object : RequestAdapter {
          override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
            calls++
            return request.copy(headers = request.headers + ("Authorization" to "Custom credential"))
          }
        }
      MockWebServer().use { server ->
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        val base = URITemplate(server.url("/").toString())
        OkHttpTransport(base, SundayHttpProblem.Factory, requestAdapter = custom).use { transport ->
          transport
            .withSecurity(listOf(binding))
            .transportResponse(Method.Get, "value")
            .body
            ?.close()
        }
        OkHttpTransport(base, SundayHttpProblem.Factory).use { transport ->
          transport
            .withSecurity(listOf(binding))
            .transportResponse(Method.Get, "value")
            .body
            ?.close()
        }
        val policy = StandardAuthenticationAdapter(emptyMap())
        OkHttpTransport(
          base,
          SundayHttpProblem.Factory,
          requestAdapter = policy,
          responseAdapter = policy,
        ).use { transport ->
          val error =
            assertThrows<RuntimeException> {
              transport.withSecurity(listOf(binding)).transportResponse(Method.Get, "value")
            }
          assertTrue(error.isRequestCancelled)
          transport.transportResponse(Method.Get, "value").body?.close()
        }
        assertEquals(1, calls)
        assertEquals(3, server.requestCount)
        assertEquals("Custom credential", server.takeRequest().getHeader("Authorization"))
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
      }
    }

  private class Stop :
    RuntimeException(),
    RequestCancellationException

  @Test
  fun `failed feedback cannot bypass split bearer rejection`() =
    runTest {
      for (failure in listOf(
        TokenProviderException(TokenProviderException.Reason.Temporary),
        Stop(),
        CancellationException(),
      )) {
        var rejections = 0
        var failAcquisition = false
        val provider =
          object : TokenProvider {
            override val identity = "feedback"

            override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

            override suspend fun acquire(request: TokenRequest): TokenSet {
              if (failAcquisition) throw TokenProviderException(TokenProviderException.Reason.Temporary)
              return TokenSet("access")
            }

            override suspend fun reject(
              request: TokenRequest,
              rejectedToken: String,
            ): Boolean {
              rejections++
              throw failure
            }
          }
        MockWebServer().use { server ->
          server.enqueue(
            MockResponse()
              .setResponseCode(401)
              .addHeader("WWW-Authenticate", "Bearer realm=api")
              .addHeader("WWW-Authenticate", "error=invalid_token"),
          )
          server.enqueue(
            MockResponse().setResponseCode(403).addHeader("WWW-Authenticate", "Bearer error=invalid_token"),
          )
          val settings =
            ClientSettings.resolve(
              server.url("/").toUri(),
              mapOf("read" to listOf(listOf(binding))),
              mapOf("identity" to ProviderCredentials(provider)),
            )
          settings.okhttpTransport(SundayHttpProblem.Factory).use { transport ->
            val generated = transport.withSecurity(listOf(binding))
            val error = assertThrows<RuntimeException> { generated.transportResponse(Method.Get, "value") }
            when (failure) {
              is CancellationException ->
                assertTrue(
                  error is CancellationException && error.causes().any { it === failure },
                )
              is Stop -> assertSame(failure, error)
              else -> assertTrue(error is TokenRejectedException)
            }
            assertEquals(1, rejections)
            assertEquals(1, server.requestCount)
            generated.transportResponse(Method.Get, "value").body?.close()
            assertEquals(1, rejections)
            failAcquisition = true
            val acquisition = assertThrows<TokenProviderException> { generated.transportResponse(Method.Get, "value") }
            assertEquals(TokenProviderException.Reason.Temporary, acquisition.reason)
            assertEquals(2, server.requestCount)
          }
        }
      }
    }

}
