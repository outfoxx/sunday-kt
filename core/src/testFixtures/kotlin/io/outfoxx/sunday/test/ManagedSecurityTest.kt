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

import io.outfoxx.sunday.DefaultFactories
import io.outfoxx.sunday.EventSource
import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.ResponseEvent
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.TokenConfiguration
import io.outfoxx.sunday.security.TokenManager
import io.outfoxx.sunday.security.TokenProvider
import io.outfoxx.sunday.security.TokenProviderException
import io.outfoxx.sunday.security.TokenRequest
import io.outfoxx.sunday.security.TokenSet
import io.outfoxx.sunday.withSecurity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real-HTTP credential and recovery checks shared by Kotlin transports. */
abstract class ManagedSecurityTest<TReq, TRes> {
  /** Creates the native transport under test. */
  abstract fun transport(
    base: URITemplate,
    manager: TokenProvider?,
  ): Transport<TReq, TRes>

  /** Executes an export with the consumer's native client. */
  abstract suspend fun executeExport(request: Request<TReq>): Int

  private val binding =
    SecurityBinding(
      "identity",
      "identity",
      SecurityBinding.Flow.ClientCredentials,
      "external",
      transport =
        SecurityBinding.CredentialTransport(
          SecurityBinding.CredentialTransport.Location.Header,
          "Authorization",
          "Bearer",
        ),
    )

  private class Provider : TokenProvider.Refreshing {
    override val identity = "application"
    var acquired = 0
    var refreshed = 0

    override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

    override suspend fun acquire(request: TokenRequest) =
      TokenSet("first-${++acquired}", refreshToken = "refresh-first")

    override suspend fun refresh(
      request: TokenRequest,
      refreshToken: String,
    ): TokenSet {
      refreshed++
      return TokenSet("renewed-$refreshed", refreshToken = "refresh-next")
    }
  }

  @Test
  fun `direct providers acquire once per attempt and exported requests have no managed lifecycle`() =
    runTest {
      var token = "first"
      var acquisitions = 0
      var rejections = 0
      val provider =
        object : TokenProvider {
          override val identity = "external"

          override fun configure(binding: SecurityBinding) = TokenConfiguration("client", "session")

          override suspend fun acquire(request: TokenRequest): TokenSet {
            acquisitions++
            if (token.isEmpty()) throw TokenProviderException()
            return TokenSet(token)
          }

          override suspend fun reject(
            request: TokenRequest,
            rejectedToken: String,
          ): Boolean {
            rejections++
            if (token == rejectedToken) token = "renewed"
            return true
          }
        }
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"))
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"))
        server.enqueue(MockResponse().setResponseCode(204))
        transport(URITemplate(server.url("/").toString()), provider).use { transport ->
          val secured = transport.withSecurity(listOf(binding))
          secured.transportResponse(Method.Get, "value")
          token = "changed"
          secured.transportResponse(Method.Get, "value")
          val exported = secured.transportRequest(Method.Get, "value")
          assertEquals(3, acquisitions)
          assertEquals(401, executeExport(exported))
          assertEquals(3, acquisitions)
          assertEquals(0, rejections)
          assertEquals(401, secured.transportResponse(Method.Get, "value").statusCode)
          secured.transportResponse(Method.Get, "value")
          assertEquals(5, acquisitions)
          assertEquals(1, rejections)
          assertEquals(
            listOf("Bearer first", "Bearer changed", "Bearer changed", "Bearer changed", "Bearer renewed"),
            (1..5).map { server.takeRequest().getHeader("Authorization") },
          )
          val old = provider.credentials(binding)
          token = "new-session"
          assertTrue(old.invalidate())
          assertEquals("new-session", provider.credentials(binding).tokens.accessToken)
          token = ""
          expectFailure { secured.transportResponse(Method.Get, "value") }
          assertEquals(5, server.requestCount)
        }
      }
    }

  @Test
  fun `native execution renews on a subsequent explicit call`() =
    runTest {
      val provider = Provider()
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"))
        repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
        TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
          transport(URITemplate(server.url("/").toString()), manager).use { transport ->
            val secured = transport.withSecurity(listOf(binding))
            assertEquals(401, secured.transportResponse(Method.Get, "value").statusCode)
            val response = secured.transportResponse(Method.Get, "value")
            assertEquals(204, response.statusCode)
            assertFalse(
              response.request.headers
                .toString()
                .contains("renewed-"),
            )
            assertEquals("Bearer first-1", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer renewed-1", server.takeRequest().getHeader("Authorization"))
            manager.invalidate(manager.credentials(binding))
            secured.transportResponse(Method.Get, "value")
            assertEquals("Bearer renewed-2", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, provider.acquired)
            assertEquals(2, provider.refreshed)
          }
        }
      }
    }

  @Test
  fun `unsafe forbidden and repeated invalid-token responses cannot replay further`() =
    runTest {
      for ((method, status, count) in listOf(
        Triple(Method.Post, 401, 1),
        Triple(Method.Get, 403, 1),
        Triple(Method.Get, 401, 1),
      )) {
        val provider = Provider()
        MockWebServer().use { server ->
          repeat(count) {
            server.enqueue(
              MockResponse().setResponseCode(status).setHeader("WWW-Authenticate", "Bearer error=invalid_token"),
            )
          }
          TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
            transport(URITemplate(server.url("/").toString()), manager).use { transport ->
              val response = transport.withSecurity(listOf(binding)).transportResponse(method, "value")
              response.body?.close()
              assertEquals(status, response.statusCode)
              assertEquals(count, server.requestCount)
              assertEquals(count - 1, provider.refreshed)
            }
          }
        }
      }
    }

  @Test
  fun `AND credentials preserve request bodies and redact query diagnostics`() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(204))
        TokenManager(mapOf("identity" to Provider()), scope = this).use { manager ->
          transport(URITemplate(server.url("/").toString()), manager).use { transport ->
            val key =
              binding.copy(
                scheme = "key",
                flow = SecurityBinding.Flow.Static,
                transport =
                  SecurityBinding.CredentialTransport(
                    SecurityBinding.CredentialTransport.Location.Query,
                    "key",
                  ),
              )
            val cookie =
              binding.copy(
                scheme = "cookie",
                flow = SecurityBinding.Flow.External,
                transport =
                  SecurityBinding.CredentialTransport(
                    SecurityBinding.CredentialTransport.Location.Cookie,
                    "session",
                  ),
              )
            val request =
              transport.withSecurity(listOf(binding, key, cookie)).transportRequest(
                Method.Post,
                "value",
                queryParameters = mapOf("untouched" to "a+b"),
                body = mapOf("value" to "same"),
                contentTypes = listOf(MediaType.JSON),
                purpose = Transport.RequestPurpose.Normal,
              )
            val response = transport.transportResponse(request)
            val sent = server.takeRequest()
            assertEquals("Bearer first-1", sent.getHeader("Authorization"))
            assertEquals("first-2", sent.requestUrl!!.queryParameter("key"))
            assertEquals("a+b", sent.requestUrl!!.queryParameter("untouched"))
            assertEquals("session=first-3", sent.getHeader("Cookie"))
            assertEquals("{\"value\":\"same\"}", sent.body.readUtf8())
            assertFalse(
              response.request.uri
                .toString()
                .contains("first-"),
            )
            assertFalse(
              response.request.headers
                .toString()
                .contains("first-"),
            )
          }
        }
      }
    }

  @Test
  fun `missing providers and credential conflicts fail before sending`() =
    runTest {
      MockWebServer().use { server ->
        transport(URITemplate(server.url("/").toString()), null).use { transport ->
          expectFailure { transport.withSecurity(listOf(binding)).transportRequest(Method.Get, "value") }
        }
        TokenManager(mapOf("identity" to Provider()), scope = this).use { manager ->
          transport(URITemplate(server.url("/").toString()), manager).use { transport ->
            expectFailure {
              transport.withSecurity(listOf(binding)).transportRequest(
                Method.Get,
                "value",
                headers =
                  mapOf(
                    "Authorization" to "existing",
                  ),
              )
            }
          }
        }
        assertEquals(0, server.requestCount)
      }
    }

  @Test
  fun `managed requests do not follow redirects and public requests do not acquire`() =
    runTest {
      val provider = Provider()
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        server.enqueue(MockResponse().setResponseCode(204))
        TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
          transport(URITemplate(server.url("/").toString()), manager).use { transport ->
            val response = transport.withSecurity(listOf(binding)).transportResponse(Method.Get, "value")
            response.body?.close()
            assertEquals(302, response.statusCode)
            assertEquals(1, server.requestCount)
            transport.withSecurity(emptyList()).transportResponse(Method.Get, "public")
            server.takeRequest()
            assertEquals(null, server.takeRequest().getHeader("Authorization"))
            assertEquals(1, provider.acquired)
          }
        }
      }
    }

  @Test
  fun `event connections emit rejection without an authentication retry`() =
    runTest {
      val provider = Provider()
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: first\n\n"))
        TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
          transport(URITemplate(server.url("/").toString()), manager).use { transport ->
            val request =
              transport
                .withSecurity(
                  listOf(binding),
                ).transportRequest<Unit>(Method.Get, "events", purpose = Transport.RequestPurpose.Events)
            val events =
              transport
                .start(
                  request,
                ).toList()
            assertEquals(listOf(401), events.filterIsInstance<ResponseEvent.Start<*>>().map { it.value.statusCode })
            assertFalse(events.any { it is ResponseEvent.Data })
            assertTrue(events.any { it is ResponseEvent.End })
            assertEquals(1, server.requestCount)
            assertEquals(0, provider.refreshed)
          }
        }
      }
    }

  @Test
  fun `event subscriptions stop at a rejected connection`() =
    runTest {
      val provider = Provider()
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: first\n\n"))
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"))
        TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
          transport(URITemplate(server.url("/").toString()), manager).use { transport ->
            val source = transport.withSecurity(listOf(binding)).eventSource(Method.Get, "events")
            source.use {
              val failed = CompletableDeferred<Unit>()
              source.onError = { error -> if (error != null) failed.complete(Unit) }
              source.connect()
              failed.await()
              assertEquals(1, server.requestCount)
              assertEquals(0, provider.refreshed)
              assertEquals("Bearer first-1", server.takeRequest().getHeader("Authorization"))
            }
          }
        }
      }
    }

  @Test
  fun `closing an event subscription cancels pending credential acquisition`() =
    runTest {
      val acquiring = CompletableDeferred<Unit>()
      val canceled = CompletableDeferred<Unit>()
      val provider =
        object : TokenProvider {
          override val identity = "pending"

          override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

          override suspend fun acquire(request: TokenRequest): TokenSet {
            acquiring.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              canceled.complete(Unit)
            }
          }
        }
      MockWebServer().use { server ->
        TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
          transport(URITemplate(server.url("/").toString()), manager).use { transport ->
            transport.withSecurity(listOf(binding)).eventSource(Method.Get, "events").use { source ->
              source.connect()
              acquiring.await()
              source.close()
              canceled.await()
              assertEquals(EventSource.ReadyState.Closed, source.readyState)
              assertEquals(0, server.requestCount)
            }
          }
        }
      }
    }

  @Test
  fun `missing event providers terminate before sending`() =
    runTest {
      MockWebServer().use { server ->
        transport(URITemplate(server.url("/").toString()), null).use { transport ->
          transport.withSecurity(listOf(binding)).eventSource(Method.Get, "events").use { source ->
            val failed = CompletableDeferred<Throwable?>()
            source.onError = { failed.complete(it) }
            source.connect()
            assertTrue(failed.await()?.cause is TokenProviderException)
            assertEquals(EventSource.ReadyState.Closed, source.readyState)
            assertEquals(0, server.requestCount)
          }
        }
      }
    }

  @Test
  fun `default transport factory forwards the application token manager`() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(204))
        TokenManager(mapOf("identity" to Provider()), scope = this).use { manager ->
          DefaultFactories
            .transport(
              URITemplate(server.url("/").toString()),
              requestAdapter =
                io.outfoxx.sunday.security
                  .AuthenticationAdapter(mapOf("identity" to manager)),
            ).use { transport ->
              val request = transport.withSecurity(listOf(binding)).transportRequest(Method.Get, "value")
              assertEquals("Bearer first-1", request.headers.first { it.first.equals("Authorization", true) }.second)
              assertEquals(0, server.requestCount)
            }
        }
      }
    }

  @Test
  fun `temporary credential outage reconnects events`() =
    runTest {
      var attempts = 0
      val provider =
        object : TokenProvider {
          override val identity = "intermittent"

          override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

          override suspend fun acquire(request: TokenRequest): TokenSet {
            if (++attempts == 1) throw TokenProviderException(TokenProviderException.Reason.Temporary)
            return TokenSet("recovered")
          }
        }
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: recovered\n\n"))
        TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
          transport(URITemplate(server.url("/").toString()), manager).use { transport ->
            transport.withSecurity(listOf(binding)).eventSource(Method.Get, "events").use { source ->
              val message = CompletableDeferred<String?>()
              source.onMessage = { message.complete(it.data) }
              source.connect()
              assertEquals("recovered", message.await())
              source.close()
              assertEquals(2, attempts)
            }
          }
        }
      }
    }

  @Test
  fun `provider rejection failure terminates recovery and allows later operations`() =
    runTest {
      val provider =
        object : TokenProvider {
          override val identity = "external"

          override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

          override suspend fun acquire(request: TokenRequest) = TokenSet("token")

          override suspend fun reject(
            request: TokenRequest,
            rejectedToken: String,
          ): Boolean = throw IllegalStateException("private-provider-detail")
        }
      MockWebServer().use { server ->
        server.enqueue(
          MockResponse()
            .setResponseCode(
              401,
            ).setHeader("WWW-Authenticate", "Bearer error=invalid_token")
            .setBody("rejected"),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        transport(URITemplate(server.url("/").toString()), provider).use { transport ->
          val authenticated = transport.withSecurity(listOf(binding))
          expectFailure { authenticated.transportResponse(Method.Get, "value") }
          assertEquals(1, server.requestCount)
          assertEquals(204, authenticated.transportResponse(Method.Get, "value").statusCode)
          assertEquals(2, server.requestCount)
        }
      }
    }

  private suspend fun expectFailure(action: suspend () -> Unit) {
    try {
      action()
    } catch (error: RuntimeException) {
      if (error is TokenProviderException || error.cause is TokenProviderException) return
      throw error
    }
    throw AssertionError("Expected credential failure")
  }
}
