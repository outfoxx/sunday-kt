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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class TokenManagerTest {
  private val binding =
    SecurityBinding(
      "token",
      "identity",
      SecurityBinding.Flow.ClientCredentials,
      "external",
      setOf("read", "write"),
      SecurityEndpoints(tokenUrl = "https://identity.example/token"),
      transport =
        SecurityBinding.CredentialTransport(
          SecurityBinding.CredentialTransport.Location.Header,
          "Authorization",
          "Bearer",
        ),
    )

  private class TestClock(
    var seconds: Long = 0,
  ) : Clock() {
    override fun instant(): Instant = Instant.ofEpochSecond(seconds)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
  }

  private open class Provider : TokenProvider.Refreshing {
    override var identity = "application"
    var configuration = TokenConfiguration("client", "session")
    var next = TokenSet("first", Instant.ofEpochSecond(100), "refresh-first")
    val acquired = mutableListOf<TokenRequest>()
    val refreshed = mutableListOf<String>()

    override fun configure(binding: SecurityBinding): TokenConfiguration = configuration

    override suspend fun acquire(request: TokenRequest): TokenSet {
      acquired += request
      return next
    }

    override suspend fun refresh(
      request: TokenRequest,
      refreshToken: String,
    ): TokenSet {
      refreshed += refreshToken
      return next
    }
  }

  @Test
  fun `expiry skew rotation and conditional invalidation`() =
    runTest {
      val clock = TestClock()
      val provider = Provider()
      TokenManager(mapOf("identity" to provider), clock = clock, scope = this).use { manager ->
        val first = manager.credentials(binding)
        assertEquals(first, manager.credentials(binding))
        assertEquals(1, provider.acquired.size)
        clock.seconds = 71
        provider.next = TokenSet("second", Instant.ofEpochSecond(200), "refresh-second")
        val second = manager.credentials(binding)
        assertEquals("second", second.tokens.accessToken)
        assertEquals(listOf("refresh-first"), provider.refreshed)
        manager.invalidate(first)
        assertEquals(second, manager.credentials(binding))
        manager.invalidate(second)
        provider.next = TokenSet("third", Instant.ofEpochSecond(300))
        val third = manager.credentials(binding)
        assertEquals("refresh-second", third.tokens.refreshToken)
        assertEquals(listOf("refresh-first", "refresh-second"), provider.refreshed)
        assertFalse(third.toString().contains("third"))
        assertFalse(third.tokens.toString().contains("refresh-second"))
      }
    }

  @Test
  fun `interactive session needs fresh authorization when it cannot refresh`() =
    runTest {
      val provider = Provider().apply { next = TokenSet("first") }
      val interactive = binding.copy(flow = SecurityBinding.Flow.AuthorizationCode)
      TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
        val initial = manager.credentials(interactive)
        manager.invalidate(initial)
        expectFailure<AuthorizationRequiredException> { manager.credentials(interactive) }
        assertEquals(1, provider.acquired.size)
        provider.configuration = provider.configuration.copy(grantIdentity = "fresh-session")
        manager.credentials(interactive)
        assertEquals(2, provider.acquired.size)
      }
    }

  @Test
  fun `failed authorization cannot retry a consumed grant and errors do not expose secrets`() =
    runTest {
      val provider =
        object : Provider() {
          override suspend fun acquire(request: TokenRequest): TokenSet {
            acquired += request
            error("SECRET")
          }
        }
      TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
        val interactive = binding.copy(flow = SecurityBinding.Flow.AuthorizationCode)
        val failure = expectFailure<TokenProviderException> { manager.credentials(interactive) }
        assertFalse(failure.toString().contains("SECRET"))
        assertFalse(failure.stackTraceToString().contains("SECRET"))
        expectFailure<AuthorizationRequiredException> { manager.credentials(interactive) }
        assertEquals(1, provider.acquired.size)
        repeat(2) { expectFailure<TokenProviderException> { manager.credentials(binding) } }
        assertEquals(3, provider.acquired.size)
      }
    }

  @Test
  fun `cache key isolates every acquisition input and ignores scope order`() =
    runTest {
      val provider = Provider()
      val providers = mapOf("identity" to provider, "alternate" to provider)
      TokenManager(providers, clock = TestClock(), scope = this).use { manager ->
        val first = manager.credentials(binding)
        assertEquals(first, manager.credentials(binding.copy(scopes = linkedSetOf("write", "read"))))
        listOf(
          binding.copy(profile = "internal"),
          binding.copy(scopes = setOf("read")),
          binding.copy(audience = "other"),
          binding.copy(resource = "api"),
          binding.copy(provider = "alternate"),
          binding.copy(flow = SecurityBinding.Flow.External),
          binding.copy(endpoints = binding.endpoints.copy(discoveryUrl = "https://id.example/discovery")),
          binding.copy(endpoints = binding.endpoints.copy(authorizationUrl = "https://id.example/authorize")),
          binding.copy(endpoints = binding.endpoints.copy(tokenUrl = "https://id.example/token")),
          binding.copy(endpoints = binding.endpoints.copy(refreshUrl = "https://id.example/refresh")),
        ).forEach { assertNotEquals(first.key, manager.credentials(it).key) }
        listOf(
          TokenConfiguration("other-client", "session"),
          TokenConfiguration("client", "other-session"),
          TokenConfiguration("client", "session", SecurityEndpoints(tokenUrl = "https://override.example/token")),
        ).forEach {
          provider.configuration = it
          assertNotEquals(first.key, manager.credentials(binding).key)
        }
        assertEquals(
          "external",
          provider.acquired
            .last()
            .binding.profile,
        )
        provider.identity = "other-provider"
        assertNotEquals(first.key, manager.credentials(binding).key)
        assertEquals(15, provider.acquired.size)
      }
    }

  @Test
  fun `canceling one waiter preserves a shared acquisition`() =
    runTest {
      val started = CompletableDeferred<Unit>()
      val finish = CompletableDeferred<Unit>()
      var canceled = false
      val provider =
        object : Provider() {
          override suspend fun acquire(request: TokenRequest): TokenSet {
            acquired += request
            started.complete(Unit)
            try {
              finish.await()
              return next
            } catch (error: CancellationException) {
              canceled = true
              throw error
            }
          }
        }
      TokenManager(mapOf("identity" to provider), clock = TestClock(), scope = this).use { manager ->
        val first = async { manager.credentials(binding) }
        started.await()
        val second = async { manager.credentials(binding) }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(canceled)
        finish.complete(Unit)
        assertEquals("first", second.await().tokens.accessToken)
        assertEquals(1, provider.acquired.size)
      }
    }

  @Test
  fun `final waiter cancellation stops acquisition and a subsequent caller can retry`() =
    runTest {
      val started = CompletableDeferred<Unit>()
      val canceled = CompletableDeferred<Unit>()
      val provider =
        object : Provider() {
          override suspend fun acquire(request: TokenRequest): TokenSet {
            acquired += request
            if (acquired.size > 1) return next
            started.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              canceled.complete(Unit)
            }
          }
        }
      TokenManager(mapOf("identity" to provider), clock = TestClock(), scope = this).use { manager ->
        val first = async { manager.credentials(binding) }
        started.await()
        first.cancelAndJoin()
        canceled.await()
        assertEquals("first", manager.credentials(binding).tokens.accessToken)
      }
    }

  @Test
  fun `canceling after rotation preserves storage and the next waiter joins it`() =
    runTest {
      val saving = CompletableDeferred<Unit>()
      val finish = CompletableDeferred<Unit>()
      var stored: TokenSet? = null
      val store =
        object : TokenStore {
          override suspend fun load(key: String): TokenSet? = stored

          override suspend fun save(
            key: String,
            tokens: TokenSet,
          ) {
            saving.complete(Unit)
            finish.await()
            stored = tokens
          }

          override suspend fun remove(key: String) {
            stored = null
          }
        }
      val provider = Provider()
      TokenManager(mapOf("identity" to provider), store = store, clock = TestClock(), scope = this).use { manager ->
        val first = async { manager.credentials(binding) }
        saving.await()
        first.cancelAndJoin()
        finish.complete(Unit)
        assertEquals(provider.next, manager.credentials(binding).tokens)
        assertEquals(provider.next, stored)
        assertEquals(1, provider.acquired.size)
      }
    }

  @Test
  fun `pre-canceled callers and closed manager never start providers`() =
    runTest {
      val provider = Provider()
      val manager = TokenManager(mapOf("identity" to provider), scope = this)
      val task = async { manager.credentials(binding) }
      task.cancelAndJoin()
      assertTrue(provider.acquired.isEmpty())
      manager.close()
      expectFailure<CancellationException> { manager.credentials(binding) }
      assertTrue(provider.acquired.isEmpty())
      assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

  @Test
  fun `missing providers invalid tokens and absent session are rejected`() =
    runTest {
      TokenManager(emptyMap(), scope = this).use { manager ->
        expectFailure<TokenProviderException> { manager.credentials(binding) }
      }
      val provider = Provider()
      listOf(TokenSet(""), TokenSet("token", Instant.EPOCH)).forEach { token ->
        provider.next = token
        TokenManager(mapOf("identity" to provider), clock = TestClock(), scope = this).use { manager ->
          expectFailure<TokenProviderException> { manager.credentials(binding) }
        }
      }
      provider.configuration = TokenConfiguration("client")
      TokenManager(mapOf("identity" to provider), scope = this).use { manager ->
        expectFailure<TokenProviderException> {
          manager.credentials(binding.copy(flow = SecurityBinding.Flow.AuthorizationCode))
        }
      }
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
