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

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI

class ClientSettingsTest {
  private val binding =
    SecurityBinding(
      scheme = "identity",
      provider = "identity",
      flow = SecurityBinding.Flow.Static,
      transport =
        SecurityBinding.CredentialTransport(
          SecurityBinding.CredentialTransport.Location.Header,
          "Authorization",
          "Bearer",
        ),
    )

  @Test
  fun `direct settings reject endpoint components that corrupt operation paths`() {
    listOf("https://user:secret@api.example/v1", "https://api.example/v1?x=1", "https://api.example/v1#part").forEach {
      assertThrows(IllegalArgumentException::class.java) { ClientSettings(URI(it)) }
    }
  }

  @Test
  fun `complete selection distinguishes same-scheme scopes`() {
    val read = binding.copy(scopes = setOf("read"))
    val write = binding.copy(scopes = setOf("write"))
    val alternatives = mapOf("list" to listOf(listOf(read), listOf(write)))
    val credentials = mapOf("identity" to BearerCredentials("token"))
    val base = URI("https://api.example")
    assertThrows(IllegalArgumentException::class.java) { ClientSettings.resolve(base, alternatives, credentials) }
    val settings = ClientSettings.resolve(base, alternatives, credentials, alternativeSelection = mapOf("list" to 1))
    assertEquals(
      setOf("write"),
      settings.bindings
        .getValue("list")
        .single()
        .scopes,
    )
    assertThrows(IllegalArgumentException::class.java) {
      ClientSettings.resolve(base, alternatives, credentials, alternativeSelection = mapOf("list" to 2))
    }
    assertThrows(IllegalArgumentException::class.java) {
      ClientSettings.resolve(base, alternatives, credentials, alternativeSelection = mapOf("typo" to 0))
    }
  }

  @Test
  fun `settings snapshot metadata and keep credentials private`() =
    runTest {
      val scopes = mutableSetOf("read")
      val settings =
        ClientSettings(
          URI("https://api.example"),
          mapOf("list" to listOf(binding.copy(scopes = scopes))),
          mapOf("identity" to BearerCredentials("private-token")),
        )
      scopes.add("write")
      assertEquals(
        setOf("read"),
        settings.bindings
          .getValue("list")
          .single()
          .scopes,
      )
      val manager = settings.tokenManager!!
      assertEquals("private-token", manager.credentials(binding).tokens.accessToken)
      assertFalse(settings.toString().contains("private-token"))
    }

  @Test
  fun `configurations isolate token managers for the same scheme`() =
    runTest {
      val first =
        ClientSettings(
          URI("https://one.example"),
          mapOf("list" to listOf(binding)),
          mapOf("identity" to BearerCredentials("one")),
        )
      val second =
        ClientSettings(
          URI("https://two.example"),
          mapOf("list" to listOf(binding)),
          mapOf("identity" to BearerCredentials("two")),
        )
      assertEquals(
        "one",
        first.tokenManager!!
          .credentials(binding)
          .tokens.accessToken,
      )
      assertEquals(
        "two",
        second.tokenManager!!
          .credentials(binding)
          .tokens.accessToken,
      )
    }

  @Test
  fun `missing and incompatible credentials fail before transport creation`() {
    assertThrows(IllegalArgumentException::class.java) {
      ClientSettings(URI("https://api.example"), mapOf("list" to listOf(binding)))
    }
    assertThrows(IllegalArgumentException::class.java) {
      ClientSettings(
        URI("https://api.example"),
        mapOf("list" to listOf(binding)),
        mapOf(
          "identity" to ApiKeyCredentials("key"),
        ),
      )
    }
  }

  @Test
  fun `OAuth construction delegates once without acquisition`() {
    val oauth = binding.copy(flow = SecurityBinding.Flow.ClientCredentials)
    var calls = 0
    val settings =
      ClientSettings.resolve(
        URI("https://api.example"),
        mapOf("list" to listOf(listOf(oauth)), "read" to listOf(listOf(oauth))),
        mapOf(
          "identity" to
            OAuthCredentials.ClientCredentials(
              OAuthTokenProvider.Configuration(
                "application",
                "client",
                "secret",
                OAuthTokenProvider.Authentication.ClientSecretBasic,
              ),
            ) {
              calls++
              object : TokenProvider {
                override val identity = "application"

                override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

                override suspend fun acquire(request: TokenRequest): TokenSet = error("Unexpected acquisition")
              }
            },
        ),
      )
    assertNotNull(settings.tokenManager)
    assertEquals(1, calls)
  }

  @Test
  fun `complete alternatives require explicit selection when ambiguous`() {
    val key =
      binding.copy(
        scheme = "key",
        provider = "key",
        transport = SecurityBinding.CredentialTransport(SecurityBinding.CredentialTransport.Location.Query, "key"),
      )
    val credentials = mapOf("identity" to BearerCredentials("one"), "key" to ApiKeyCredentials("two"))
    val alternatives = mapOf("list" to listOf(listOf(binding, key), listOf(binding)), "public" to listOf(emptyList()))
    assertThrows(IllegalArgumentException::class.java) {
      ClientSettings.resolve(URI("https://api.example"), alternatives, credentials)
    }
    val settings =
      ClientSettings.resolve(
        URI("https://api.example"),
        alternatives,
        credentials,
        mapOf(
          "list" to setOf("identity", "key"),
        ),
      )
    assertEquals(listOf("identity", "key"), settings.bindings.getValue("list").map { it.scheme })
    assertEquals(emptyList<SecurityBinding>(), settings.bindings.getValue("public"))
  }

  @Test
  fun `relative endpoints resolve against the document and security templates are rejected`() {
    val endpoint =
      ClientSettings.serverURL(
        "../{version}",
        mapOf("version" to "v2"),
        URI("https://api.example/spec/openapi.yaml"),
      )
    assertEquals(URI("https://api.example/v2"), endpoint)
    val settings =
      ClientSettings(
        endpoint,
        mapOf("list" to listOf(binding.copy(endpoints = SecurityEndpoints(tokenUrl = "oauth/token")))),
        mapOf(
          "identity" to BearerCredentials("secret"),
        ),
      )
    assertEquals(
      "https://api.example/oauth/token",
      settings.bindings
        .getValue("list")
        .single()
        .endpoints.tokenUrl,
    )
    assertThrows(IllegalArgumentException::class.java) { ClientSettings.serverURL("/v2", emptyMap()) }
    assertThrows(IllegalArgumentException::class.java) {
      ClientSettings(
        endpoint,
        mapOf(
          "list" to listOf(binding.copy(endpoints = SecurityEndpoints(tokenUrl = "/{version}/token"))),
        ),
        mapOf("identity" to BearerCredentials("secret")),
      )
    }
  }

  @Test
  fun `static credential families encode their wire values without exposing secrets`() =
    runTest {
      val basic = binding.copy(transport = binding.transport.copy(prefix = "Basic"))
      val key = binding.copy(transport = binding.transport.copy(prefix = null, name = "X-Key"))
      for ((selected, credential, expected) in listOf(
        Triple(basic, BasicCredentials("alice", "secret"), "YWxpY2U6c2VjcmV0"),
        Triple(key, ApiKeyCredentials("secret"), "secret"),
      )) {
        val settings =
          ClientSettings(
            URI("https://api.example"),
            mapOf("list" to listOf(selected)),
            mapOf("identity" to credential),
          )
        assertEquals(
          expected,
          settings.tokenManager!!
            .credentials(selected)
            .tokens.accessToken,
        )
        assertFalse(credential.toString().contains("secret"))
      }
      assertEquals(null, ClientSettings(URI("https://api.example")).tokenManager)
    }

  @Test
  fun `custom providers retain acquisition ownership and enforce selected flow`() =
    runTest {
      var acquisitions = 0
      val provider =
        object : TokenProvider {
          override val identity = "custom"

          override fun configure(binding: SecurityBinding) = TokenConfiguration(identity)

          override suspend fun acquire(request: TokenRequest): TokenSet {
            acquisitions++
            return TokenSet("custom-token")
          }
        }
      for (flow in listOf(null, SecurityBinding.Flow.Static)) {
        val credentials = ProviderCredentials(provider, flow)
        val settings =
          ClientSettings(
            URI("https://api.example"),
            mapOf("list" to listOf(binding)),
            mapOf("identity" to credentials),
          )
        assertFalse(credentials.toString().contains("custom-token"))
        assertEquals(
          "custom-token",
          settings.tokenManager!!
            .credentials(binding)
            .tokens.accessToken,
        )
      }
      assertEquals(2, acquisitions)
      assertThrows(IllegalArgumentException::class.java) {
        ClientSettings(
          URI("https://api.example"),
          mapOf("list" to listOf(binding)),
          mapOf("identity" to ProviderCredentials(provider, SecurityBinding.Flow.AuthorizationCode)),
        )
      }
      assertThrows(IllegalArgumentException::class.java) {
        ClientSettings(
          URI("https://api.example"),
          mapOf("list" to listOf(binding, binding.copy(scheme = "other"))),
          mapOf("identity" to ProviderCredentials(provider), "other" to ProviderCredentials(provider)),
        )
      }
      assertEquals(2, acquisitions)
    }

  @Test
  fun `OAuth grant validation precedes provider construction and resolves credential endpoints`() {
    val codeBinding = binding.copy(flow = SecurityBinding.Flow.AuthorizationCode)
    var calls = 0
    val providerFactory: (OAuthTokenProvider.Configuration) -> TokenProvider = { configuration ->
      calls++
      assertEquals("https://api.example/oauth/token", configuration.endpoints.tokenUrl)
      object : TokenProvider {
        override val identity = "application"

        override fun configure(binding: SecurityBinding): TokenConfiguration = error("Unexpected configuration")

        override suspend fun acquire(request: TokenRequest): TokenSet = error("Unexpected acquisition")
      }
    }
    val configuration =
      OAuthTokenProvider.Configuration(
        "application",
        "client",
        grantIdentity = "session",
        authorization = { error("Unexpected authorization") },
        endpoints = SecurityEndpoints(tokenUrl = "oauth/token"),
      )
    val credential = OAuthCredentials.AuthorizationCode(configuration, providerFactory)
    val settings =
      ClientSettings.resolve(
        URI("https://api.example/v1"),
        mapOf("list" to listOf(listOf(codeBinding))),
        mapOf("identity" to credential),
      )
    assertNotNull(settings.tokenManager)
    assertEquals(1, calls)
    assertFalse(credential.toString().contains("session"))
    for (invalid in listOf(
      configuration.copy(identity = ""),
      configuration.copy(clientId = ""),
      configuration.copy(grantIdentity = null),
      configuration.copy(authorization = null),
      configuration.copy(clientSecret = "secret"),
      configuration.copy(clientSecret = "", authentication = OAuthTokenProvider.Authentication.ClientSecretBasic),
    )) {
      assertThrows(IllegalArgumentException::class.java) {
        ClientSettings(
          URI("https://api.example"),
          mapOf("list" to listOf(codeBinding)),
          mapOf("identity" to OAuthCredentials.AuthorizationCode(invalid, providerFactory)),
        )
      }
    }
    val clientCredentials = OAuthCredentials.ClientCredentials(configuration, providerFactory)
    assertFalse(clientCredentials.toString().contains("session"))
    assertThrows(IllegalArgumentException::class.java) {
      ClientSettings(
        URI("https://api.example"),
        mapOf(
          "list" to listOf(codeBinding.copy(flow = SecurityBinding.Flow.ClientCredentials)),
        ),
        mapOf("identity" to clientCredentials),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      ClientSettings(
        URI("https://api.example"),
        mapOf("list" to listOf(codeBinding)),
        mapOf("identity" to BearerCredentials("token")),
      )
    }
    assertEquals(1, calls)
  }

}
