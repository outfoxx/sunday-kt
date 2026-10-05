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

import io.outfoxx.sunday.security.AuthorizationGrant
import io.outfoxx.sunday.security.AuthorizationRequiredException
import io.outfoxx.sunday.security.OAuthTokenProvider
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.SecurityEndpoints
import io.outfoxx.sunday.security.TokenManager
import io.outfoxx.sunday.security.TokenProvider
import io.outfoxx.sunday.security.TokenProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Common real-HTTP OAuth lifecycle checks required of each Kotlin HTTP backend. */
abstract class OAuthTokenProviderTest {
  /** Creates a provider whose HTTP backend is supplied by the concrete fixture. */
  abstract fun provider(configuration: OAuthTokenProvider.Configuration): TokenProvider.Refreshing

  private fun binding(server: MockWebServer) =
    SecurityBinding(
      "identity",
      "identity",
      SecurityBinding.Flow.ClientCredentials,
      "external",
      setOf("read", "write"),
      SecurityEndpoints(tokenUrl = server.url("/token").toString()),
      "api",
      "urn:api",
      SecurityBinding.CredentialTransport(
        SecurityBinding.CredentialTransport.Location.Header,
        "Authorization",
        "Bearer",
      ),
    )

  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

  @Test
  fun `client credentials and rotating refresh use explicit client authentication`() =
    runTest {
      for (authentication in listOf(
        OAuthTokenProvider.Authentication.ClientSecretBasic,
        OAuthTokenProvider.Authentication.ClientSecretPost,
      )) {
        MockWebServer().use { server ->
          repeat(3) { index ->
            server.enqueue(MockResponse().setBody(discoveryMetadata(server, "[\"${authentication.wireName}\"]")))
            server.enqueue(
              MockResponse().setBody(
                """{"access_token":"token-${index + 1}","token_type":"Bearer",
                "refresh_token":"refresh-${index + 1}","expires_in":60}""",
              ),
            )
          }
          val configuration =
            OAuthTokenProvider.Configuration(
              "application",
              "client:name",
              "s e:c",
              authentication,
              issuer = "https://trusted.example",
              clock = clock,
            )
          TokenManager(mapOf("identity" to provider(configuration)), clock = clock, scope = this).use { manager ->
            repeat(3) { index ->
              val binding = discoveryBinding(server).copy(flow = SecurityBinding.Flow.ClientCredentials)
              val lease = manager.credentials(binding)
              assertEquals(Instant.ofEpochSecond(60), lease.tokens.expiresAt)
              manager.invalidate(lease)
              assertEquals("/discovery", server.takeRequest().path)
              val request = server.takeRequest()
              val form = parseForm(request.body.readUtf8())
              assertEquals("POST", request.method)
              assertEquals("read write", form["scope"])
              assertEquals("api", form["audience"])
              assertEquals("urn:api", form["resource"])
              assertEquals(if (index == 0) "client_credentials" else "refresh_token", form["grant_type"])
              if (index > 0) assertEquals("refresh-$index", form["refresh_token"])
              if (authentication == OAuthTokenProvider.Authentication.ClientSecretBasic) {
                assertEquals(
                  "Basic " + Base64.getEncoder().encodeToString("client%3Aname:s+e%3Ac".toByteArray(UTF_8)),
                  request.getHeader("Authorization"),
                )
                assertFalse(form.containsKey("client_secret"))
              } else {
                assertEquals("client:name", form["client_id"])
                assertEquals("s e:c", form["client_secret"])
              }
            }
          }
        }
      }
    }

  @Test
  fun `PKCE code is never exchanged again and invalid refresh requires application authorization`() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(
          MockResponse().setBody("""{"token_type":"Bearer","access_token":"first","refresh_token":"rotating"}"""),
        )
        server.enqueue(
          MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"SECRET"}"""),
        )
        val configuration =
          OAuthTokenProvider.Configuration(
            "application",
            "public-client",
            grantIdentity = "session",
            authorization = { AuthorizationGrant("fresh-code", "https://app.example/callback", "v".repeat(43)) },
            clock = clock,
          )
        val binding = binding(server).copy(flow = SecurityBinding.Flow.AuthorizationCode)
        TokenManager(mapOf("identity" to provider(configuration)), clock = clock, scope = this).use { manager ->
          manager.invalidate(manager.credentials(binding))
          val first = parseForm(server.takeRequest().body.readUtf8())
          assertEquals("authorization_code", first["grant_type"])
          assertEquals("fresh-code", first["code"])
          assertEquals("v".repeat(43), first["code_verifier"])
          assertEquals("https://app.example/callback", first["redirect_uri"])
          assertEquals("public-client", first["client_id"])
          expectFailure<AuthorizationRequiredException> { manager.credentials(binding) }
          val refresh = parseForm(server.takeRequest().body.readUtf8())
          assertEquals("refresh_token", refresh["grant_type"])
          assertFalse(refresh.containsKey("code"))
          expectFailure<AuthorizationRequiredException> { manager.credentials(binding.copy(scopes = setOf("read"))) }
          assertEquals(2, server.requestCount)
        }
      }
    }

  @Test
  fun `discovery uses separately configured issuer and does not replace endpoint override`() =
    runTest {
      MockWebServer().use { server ->
        val metadata = """{"issuer":"https://trusted.example","token_endpoint":"https://unused.example/token"}"""
        repeat(2) {
          server.enqueue(MockResponse().setBody(metadata))
          server.enqueue(MockResponse().setBody("""{"token_type":"bearer","access_token":"token"}"""))
        }
        val configuration =
          OAuthTokenProvider.Configuration(
            "application",
            "client",
            "secret",
            OAuthTokenProvider.Authentication.ClientSecretBasic,
            endpoints = SecurityEndpoints(tokenUrl = server.url("/override").toString()),
            issuer = "https://trusted.example",
          )
        val binding =
          binding(
            server,
          ).copy(endpoints = SecurityEndpoints(discoveryUrl = server.url("/discovery").toString()))
        TokenManager(mapOf("identity" to provider(configuration)), scope = this).use { manager ->
          manager.credentials(binding)
          manager.credentials(binding.copy(scopes = setOf("read")))
        }
        assertEquals(
          listOf("/discovery", "/override", "/discovery", "/override"),
          List(4) { server.takeRequest().path },
        )
        server.enqueue(MockResponse().setBody(metadata))
        TokenManager(
          mapOf("identity" to provider(configuration.copy(issuer = "https://other.example"))),
          scope = this,
        ).use {
          expectFailure<TokenProviderException> { it.credentials(binding) }
        }
        assertEquals(5, server.requestCount)
      }
    }

  @Test
  fun `public PKCE discovery permits omitted none and revalidates rotating refresh`() =
    runTest {
      for (methods in listOf(
        """["private_key_jwt","client_secret_basic","client_secret_post","tls_client_auth","client_secret_jwt"]""",
        null,
        """["none"]""",
        "[]",
      )) {
        MockWebServer().use { server ->
          val metadata = discoveryMetadata(server, methods)
          repeat(3) { index ->
            server.enqueue(MockResponse().setBody(metadata))
            server.enqueue(
              MockResponse().setBody(
                """{"access_token":"token-${index + 1}","token_type":"Bearer",
                "refresh_token":"refresh-${index + 1}","expires_in":60}""",
              ),
            )
          }
          var authorizations = 0
          val configuration =
            OAuthTokenProvider.Configuration(
              "application",
              "public-client",
              grantIdentity = "session",
              issuer = "https://trusted.example",
              authorization = { request ->
                authorizations++
                assertEquals(server.url("/authorize").toString(), request.binding.endpoints.authorizationUrl)
                AuthorizationGrant("fresh-code", "https://app.example/callback", "v".repeat(43))
              },
              clock = clock,
            )
          val binding = discoveryBinding(server)
          TokenManager(mapOf("identity" to provider(configuration)), clock = clock, scope = this).use { manager ->
            repeat(3) { index ->
              val lease = manager.credentials(binding)
              assertEquals("token-${index + 1}", lease.tokens.accessToken)
              assertEquals("refresh-${index + 1}", lease.tokens.refreshToken)
              manager.invalidate(lease)
              assertEquals("/discovery", server.takeRequest().path)
              val request = server.takeRequest()
              assertEquals("/token", request.path)
              assertEquals("POST", request.method)
              assertEquals(null, request.getHeader("Authorization"))
              val form = parseForm(request.body.readUtf8())
              assertEquals("public-client", form["client_id"])
              assertFalse(form.containsKey("client_secret"))
              assertEquals(if (index == 0) "authorization_code" else "refresh_token", form["grant_type"])
              if (index == 0) {
                assertEquals("fresh-code", form["code"])
                assertEquals("v".repeat(43), form["code_verifier"])
                assertEquals("https://app.example/callback", form["redirect_uri"])
              } else {
                assertEquals("refresh-$index", form["refresh_token"])
                assertFalse(form.containsKey("code"))
              }
            }
            // Overrides must not let either acquisition or renewal bypass discovery trust.
            repeat(2) {
              server.enqueue(MockResponse().setBody(metadata.replace("trusted.example", "untrusted.example")))
            }
            expectFailure<TokenProviderException> { manager.credentials(binding) }
            expectFailure<TokenProviderException> {
              manager.credentials(
                binding.copy(endpoints = binding.endpoints.copy(tokenUrl = server.url("/override").toString())),
              )
            }
          }
          assertEquals(1, authorizations)
          assertEquals(8, server.requestCount)
        }
      }
    }

  @Test
  fun `invalid discovery methods fail before authorization or token exchange`() =
    runTest {
      for (authentication in OAuthTokenProvider.Authentication.entries) {
        val malformed =
          listOf("null", "{}", "42", "\"none\"", "[null]", "[42]", "[\"none\",{}]", "[\"client_secret_basic\",42]")
        val unsupported =
          when (authentication) {
            OAuthTokenProvider.Authentication.None -> emptyList()
            OAuthTokenProvider.Authentication.ClientSecretBasic ->
              listOf(
                "[]",
                "[\"none\"]",
                "[\"client_secret_post\"]",
              )
            OAuthTokenProvider.Authentication.ClientSecretPost ->
              listOf(
                null,
                "[]",
                "[\"none\"]",
                "[\"client_secret_basic\"]",
              )
          }
        for (methods in malformed + unsupported) {
          MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(discoveryMetadata(server, methods)))
            var authorizations = 0
            val configuration =
              OAuthTokenProvider.Configuration(
                "application",
                "client",
                clientSecret = if (authentication == OAuthTokenProvider.Authentication.None) null else "SECRET",
                authentication = authentication,
                grantIdentity = "session",
                issuer = "https://trusted.example",
                authorization = {
                  authorizations++
                  AuthorizationGrant("SECRET", "https://app.example/callback", "v".repeat(43))
                },
              )
            TokenManager(mapOf("identity" to provider(configuration)), scope = this).use { manager ->
              val error = expectFailure<TokenProviderException> { manager.credentials(discoveryBinding(server)) }
              assertEquals(TokenProviderException.Reason.Unavailable, error.reason)
              assertFalse(error.stackTraceToString().contains("SECRET"))
            }
            assertEquals(0, authorizations)
            assertEquals(1, server.requestCount)
          }
        }
      }
    }

  @Test
  fun `public discovery requires independently trusted issuer before authorization`() =
    runTest {
      for (issuer in listOf(null, "https://other.example")) {
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setBody(discoveryMetadata(server, null)))
          var authorizations = 0
          val configuration =
            OAuthTokenProvider.Configuration(
              "application",
              "public-client",
              grantIdentity = "session",
              issuer = issuer,
              endpoints = SecurityEndpoints(tokenUrl = server.url("/override").toString()),
              authorization = {
                authorizations++
                AuthorizationGrant("SECRET", "https://app.example/callback", "v".repeat(43))
              },
            )
          TokenManager(mapOf("identity" to provider(configuration)), scope = this).use { manager ->
            expectFailure<TokenProviderException> { manager.credentials(discoveryBinding(server)) }
          }
          assertEquals(0, authorizations)
          assertEquals(if (issuer == null) 0 else 1, server.requestCount)
        }
      }
    }

  @Test
  fun `redirects and malformed token responses fail safely`() =
    runTest {
      val responses =
        listOf(
          MockResponse().setResponseCode(302).setHeader("Location", "https://other.example"),
          MockResponse().setBody("""{"access_token":"secret","token_type":"unsupported"}"""),
          MockResponse().setBody("""{"access_token":"secret","token_type":"bearer","expires_in":-1}"""),
          MockResponse().setBody("""{"access_token":"secret","token_type":"bearer","scope":"read"}"""),
          MockResponse().setResponseCode(400).setBody("""{"error":"invalid_client","error_description":"SECRET"}"""),
        )
      MockWebServer().use { server ->
        val configuration =
          OAuthTokenProvider.Configuration(
            "application",
            "client",
            "secret",
            OAuthTokenProvider.Authentication.ClientSecretPost,
          )
        responses.forEach { response ->
          server.enqueue(response)
          TokenManager(mapOf("identity" to provider(configuration)), scope = this).use { manager ->
            val error = expectFailure<TokenProviderException> { manager.credentials(binding(server)) }
            assertFalse(error.stackTraceToString().contains("SECRET"))
          }
        }
        assertEquals(responses.size, server.requestCount)
      }
    }

  @Test
  fun `canceling the last waiter cancels an in-flight HTTP exchange`() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val configuration =
          OAuthTokenProvider.Configuration(
            "application",
            "client",
            "secret",
            OAuthTokenProvider.Authentication.ClientSecretPost,
          )
        TokenManager(mapOf("identity" to provider(configuration)), scope = this).use { manager ->
          val acquisition = async { manager.credentials(binding(server)) }
          assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
          acquisition.cancelAndJoin()
          expectFailure<CancellationException> { acquisition.await() }
        }
      }
    }


  @Test
  fun `token errors distinguish temporary outages from rejected refresh grants`() =
    runTest {
      MockWebServer().use { server ->
        val provider =
          provider(
            OAuthTokenProvider.Configuration(
              "application",
              "client",
              "secret",
              OAuthTokenProvider.Authentication.ClientSecretPost,
            ),
          )
        val responses =
          listOf(
            Triple(503, "upstream unavailable", TokenProviderException.Reason.Temporary),
            Triple(429, "rate limited", TokenProviderException.Reason.Temporary),
            Triple(400, """{"error":"temporarily_unavailable"}""", TokenProviderException.Reason.Temporary),
            Triple(400, """{"error":"invalid_grant"}""", TokenProviderException.Reason.InvalidGrant),
            Triple(400, """{"error":"invalid_client"}""", TokenProviderException.Reason.Unavailable),
            Triple(200, "{malformed", TokenProviderException.Reason.Unavailable),
            Triple(400, "{malformed", TokenProviderException.Reason.Unavailable),
          )
        for ((status, body, reason) in responses) {
          server.enqueue(MockResponse().setResponseCode(status).setBody(body))
          TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
            assertEquals(reason, expectFailure<TokenProviderException> { manager.credentials(binding(server)) }.reason)
          }
        }
      }
    }

  @Test
  fun `malformed discovery metadata is a terminal provider failure`() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("{malformed"))
        val configuration =
          OAuthTokenProvider.Configuration(
            "application",
            "client",
            "secret",
            OAuthTokenProvider.Authentication.ClientSecretBasic,
            issuer = "https://trusted.example",
          )
        val binding =
          binding(server).copy(endpoints = SecurityEndpoints(discoveryUrl = server.url("/discovery").toString()))
        TokenManager(mapOf("identity" to provider(configuration)), scope = this).use { manager ->
          assertEquals(
            TokenProviderException.Reason.Unavailable,
            expectFailure<TokenProviderException> { manager.credentials(binding) }.reason,
          )
        }
        assertEquals(1, server.requestCount)
      }
    }

  private fun discoveryBinding(server: MockWebServer) =
    binding(server).copy(
      flow = SecurityBinding.Flow.AuthorizationCode,
      endpoints = SecurityEndpoints(discoveryUrl = server.url("/discovery").toString()),
    )

  private fun discoveryMetadata(
    server: MockWebServer,
    methods: String?,
  ): String {
    val authentication = methods?.let { ",\"token_endpoint_auth_methods_supported\":$it" } ?: ""
    return """{"issuer":"https://trusted.example","token_endpoint":"${server.url("/token")}",
      "authorization_endpoint":"${server.url("/authorize")}"$authentication}"""
  }

  private fun parseForm(value: String): Map<String, String> =
    value.split("&").associate {
      val (key, entry) = it.split("=", limit = 2)
      URLDecoder.decode(key, UTF_8.name()) to URLDecoder.decode(entry, UTF_8.name())
    }

  private suspend inline fun <reified T : Throwable> expectFailure(action: suspend () -> Unit): T {
    try {
      action()
    } catch (error: Throwable) {
      if (error is T) return error
      throw error
    }
    throw AssertionError("Expected ${T::class.simpleName}")
  }
}
