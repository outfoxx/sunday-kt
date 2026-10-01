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

package io.outfoxx.sunday.security

import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class RequestSecurityTest {
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

  private class Provider : TokenProvider {
    override val identity = "application"
    var acquired = 0

    override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

    override suspend fun acquire(request: TokenRequest): TokenSet = TokenSet("token-${++acquired}")
  }

  @Test
  fun `complete AND attachment preserves encoded paths and unrelated query and headers`() =
    runTest {
      val provider = Provider()
      val uri = URI("https://api.example/a%2Fb?z=a%2Bb&keep=one%20two")
      val bindings =
        listOf(
          binding,
          binding.copy(
            scheme = "key",
            flow = SecurityBinding.Flow.Static,
            transport = SecurityBinding.CredentialTransport(SecurityBinding.CredentialTransport.Location.Query, "key"),
          ),
          binding.copy(
            scheme = "cookie",
            flow = SecurityBinding.Flow.External,
            transport =
              SecurityBinding.CredentialTransport(
                SecurityBinding.CredentialTransport.Location.Cookie,
                "session",
              ),
          ),
        )
      TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
        val security = RequestSecurity(bindings, manager)
        val headers = listOf("X-Trace" to "trace", "Cookie" to "existing=untouched")
        val credentials = security.authorize(uri, headers)
        assertEquals("https://api.example/a%2Fb?z=a%2Bb&keep=one%20two&key=token-2", credentials.uri.toString())
        assertTrue(credentials.headers.contains("Authorization" to "Bearer token-1"))
        assertTrue(credentials.headers.contains("Cookie" to "existing=untouched; session=token-3"))
        assertTrue(credentials.headers.contains("X-Trace" to "trace"))
        assertEquals(2, headers.size)
        val redacted = security.redact(credentials.uri, credentials.headers)
        assertFalse(redacted.uri.toString().contains("token-"))
        assertFalse(redacted.headers.toString().contains("token-"))
        assertFalse(credentials.toString().contains("token-"))
      }
    }

  @Test
  fun `missing provider and conflicting credential locations fail before acquisition`() =
    runTest {
      val provider = Provider()
      val uri = URI("https://api.example/value")
      TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
        expectFailure { RequestSecurity(listOf(binding), null).authorize(uri, emptyList()) }
        expectFailure { RequestSecurity(listOf(binding, binding), manager).authorize(uri, emptyList()) }
        expectFailure {
          RequestSecurity(
            listOf(binding),
            manager,
          ).authorize(uri, listOf("authorization" to "existing"))
        }
        assertEquals(0, provider.acquired)
      }
    }

  @Test
  fun `recovery expires only bearer credentials and preserves API key conjuncts`() =
    runTest {
      val provider = Provider()
      val key =
        binding.copy(
          scheme = "key",
          flow = SecurityBinding.Flow.Static,
          transport = SecurityBinding.CredentialTransport(SecurityBinding.CredentialTransport.Location.Query, "key"),
        )
      TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
        val security = RequestSecurity(listOf(binding, key), manager)
        val credentials = security.authorize(URI("https://api.example/value"), emptyList())
        assertTrue(security.recover(Method.Get, false, credentials, response(401, "Bearer error=invalid_token")))
        val refreshed = security.authorize(credentials.uri, credentials.headers, credentials)
        assertTrue(refreshed.headers.contains("Authorization" to "Bearer token-3"))
        assertEquals(credentials.uri, refreshed.uri)
        assertEquals(3, provider.acquired)
      }
    }

  @Test
  fun `forbidden unsafe and malformed challenge responses never recover`() =
    runTest {
      TokenManager(mapOf("identity" to Provider()), scope = this).use { manager ->
        val security = RequestSecurity(listOf(binding), manager)
        val credentials = security.authorize(URI("https://api.example/value"), emptyList())
        assertFalse(security.recover(Method.Post, false, credentials, response(401, "Bearer error=invalid_token")))
        assertFalse(security.recover(Method.Get, true, credentials, response(401, "Bearer error=invalid_token")))
        assertFalse(security.recover(Method.Get, false, credentials, response(403, "Bearer error=invalid_token")))
        listOf(
          "Basic realm=api, error=invalid_token",
          "Bearer error=insufficient_scope",
          "Bearer error=INVALID_TOKEN",
          "Bearer realm=api, Basic, error=invalid_token",
          "Bearer realm=\"unterminated, error=invalid_token",
        ).forEach { challenge ->
          assertFalse(security.recover(Method.Get, false, credentials, response(401, challenge)))
        }
        val challenges =
          listOf(
            "Bearer realm=\"x,y\", error=\"invalid_token\"",
            "Basic realm=api, Bearer error=invalid_token",
          )
        challenges.forEach { challenge ->
          assertTrue(security.recover(Method.Get, false, credentials, response(401, challenge)))
        }
      }
    }

  private fun response(
    status: Int,
    challenge: String,
  ): Response =
    object : Response {
      override val statusCode = status
      override val reasonPhrase: String? = null
      override val headers: Headers = listOf("WWW-Authenticate" to challenge)
      override val body = null
      override val trailers = null
      override val request: Request get() = error("unused")
    }

  private suspend fun expectFailure(action: suspend () -> Unit) {
    try {
      action()
    } catch (_: TokenProviderException) {
      return
    }
    throw AssertionError("Expected a safe credential failure")
  }
}
