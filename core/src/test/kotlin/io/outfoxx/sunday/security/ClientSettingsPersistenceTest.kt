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

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ClientSettingsPersistenceTest {
  private val binding =
    SecurityBinding(
      scheme = "identity",
      provider = "resolved-provider",
      flow = SecurityBinding.Flow.AuthorizationCode,
      profile = "development",
      transport =
        SecurityBinding.CredentialTransport(
          SecurityBinding.CredentialTransport.Location.Header,
          "Authorization",
          "Bearer",
        ),
    )

  @Test
  fun `application storage preserves sessions rotation isolation and logout`() =
    runBlocking {
      val values = mutableMapOf<String, TokenSet>()
      var reads = 0
      var saves = 0
      var acquisitions = 0
      var factories = 0
      val refreshes = mutableListOf<String>()
      val store =
        object : TokenStore {
          override suspend fun load(key: String): TokenSet? {
            reads++
            return values[key]
          }

          override suspend fun save(
            key: String,
            tokens: TokenSet,
          ) {
            saves++
            values[key] = tokens
          }

          override suspend fun remove(key: String) {
            values.remove(key)
          }
        }
      val managers = mutableListOf<TokenManager>()

      fun settings(
        time: Long,
        session: String = "session",
        profile: String = "development",
        direct: Boolean = false,
      ): ClientSettings {
        val provider =
          object : TokenProvider.Refreshing {
            override val identity = "application"

            override fun configure(binding: SecurityBinding) = TokenConfiguration("client", session)

            override suspend fun acquire(request: TokenRequest): TokenSet {
              acquisitions++
              return TokenSet("initial", Instant.ofEpochSecond(100), "refresh-1")
            }

            override suspend fun refresh(
              request: TokenRequest,
              refreshToken: String,
            ): TokenSet {
              refreshes += refreshToken
              return TokenSet(
                "rotated-${refreshes.size}",
                Instant.ofEpochSecond(time + 100),
                "refresh-${refreshes.size + 1}",
              )
            }
          }
        val factory: TokenManagerFactory = { providers ->
          factories++
          assertEquals(mapOf("resolved-provider" to provider), providers)
          TokenManager(
            providers,
            store,
            Duration.ofSeconds(5),
            Clock.fixed(Instant.ofEpochSecond(time), ZoneOffset.UTC),
            this,
          ).also {
            managers +=
              it
          }
        }
        val selected = binding.copy(profile = profile)
        val credentials = mapOf("identity" to ProviderCredentials(provider))
        return if (direct) {
          ClientSettings(URI("https://api.example"), mapOf("read" to listOf(selected)), credentials, factory)
        } else {
          ClientSettings.resolve(
            URI("https://api.example"),
            mapOf(
              "read" to listOf(listOf(selected)),
              "other" to listOf(listOf(selected)),
              "public" to listOf(emptyList()),
            ),
            credentials,
            tokenManagerFactory = factory,
          )
        }
      }

      suspend fun token(settings: ClientSettings) =
        settings.tokenManager!!.credentials(settings.bindings.getValue("read").single()).tokens
      try {
        val first = settings(0)
        assertEquals(listOf(0, 0, 0, 1), listOf(reads, saves, acquisitions, factories))
        val lease = first.tokenManager!!.credentials(first.bindings.getValue("read").single())
        assertEquals("initial", lease.tokens.accessToken)
        assertEquals("initial", token(settings(0, direct = true)).accessToken)
        assertEquals(1, acquisitions)
        val third = settings(96)
        assertEquals(setOf("rotated-1"), (1..20).map { async { token(third).accessToken } }.awaitAll().toSet())
        assertEquals(listOf("refresh-1"), refreshes)
        assertEquals("refresh-2", token(settings(96)).refreshToken)
        token(settings(192))
        assertEquals(listOf("refresh-1", "refresh-2"), refreshes)
        assertEquals(3, saves)
        token(settings(0, session = "other-session"))
        token(settings(0, profile = "production"))
        assertEquals(3, acquisitions)
        store.remove(lease.key)
        token(settings(0))
        assertEquals(4, acquisitions)
      } finally {
        managers.forEach { it.close() }
      }
    }

  @Test
  fun `public and invalid settings skip factory and factory failures propagate`() {
    val factory: TokenManagerFactory = { error("application factory failed") }
    assertNull(ClientSettings(URI("https://api.example"), tokenManagerFactory = factory).tokenManager)
    assertNull(
      ClientSettings
        .resolve(
          URI("https://api.example"),
          mapOf("public" to listOf(emptyList())),
          emptyMap(),
          tokenManagerFactory = factory,
        ).tokenManager,
    )
    assertThrows(IllegalArgumentException::class.java) {
      ClientSettings(URI("https://api.example"), mapOf("read" to listOf(binding)), tokenManagerFactory = factory)
    }
    assertThrows(IllegalStateException::class.java) {
      ClientSettings(
        URI("https://api.example"),
        mapOf(
          "read" to listOf(binding.copy(flow = SecurityBinding.Flow.Static)),
        ),
        mapOf(
          "identity" to BearerCredentials("secret"),
        ),
        factory,
      )
    }
  }
}
