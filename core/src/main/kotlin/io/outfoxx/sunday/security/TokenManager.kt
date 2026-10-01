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

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared credential cache with one renewal per provider/client/profile/grant key.
 * Canceling one caller leaves other waiters running; canceling the last cancels acquisition.
 * Completed rotation is saved even when every caller cancels. Close this manager at application shutdown.
 */
class TokenManager(
  providers: Map<String, TokenProvider>,
  private val store: TokenStore = MemoryTokenStore(),
  private val expirySkew: Duration = Duration.ofSeconds(30),
  private val clock: Clock = Clock.systemUTC(),
  scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : Closeable {
  private val providers = providers.toMap()
  private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
  private val guard = Any()
  private val renewals = mutableMapOf<String, Renewal>()
  private val locks = mutableMapOf<String, KeyLock>()
  private val authorizationAttempts = ConcurrentHashMap.newKeySet<String>()
  private val mapper = ObjectMapper()

  init {
    require(!expirySkew.isNegative) { "Invalid token expiry skew" }
  }

  /** Obtains current credentials without reusing a failed or consumed authorization grant. */
  suspend fun credentials(binding: SecurityBinding): TokenLease {
    currentCoroutineContext().ensureActive()
    val (provider, request, key) = resolve(binding)
    val renewal =
      synchronized(guard) {
        val existing = renewals[key]?.takeUnless { it.task.isCancelled }
        val selected =
          existing ?: Renewal().also { created ->
            created.task = scope.async(start = CoroutineStart.LAZY) { renew(key, provider, request) }
            renewals[key] = created
            created.task.invokeOnCompletion {
              synchronized(guard) { if (renewals[key] === created) renewals.remove(key) }
            }
          }
        selected.waiters++
        selected
      }
    try {
      return renewal.task.await()
    } finally {
      synchronized(guard) {
        renewal.waiters--
        if (renewal.waiters == 0 && !renewal.committing && !renewal.task.isCompleted) renewal.task.cancel()
      }
    }
  }

  /** Expires rejected credentials without removing refresh state or a concurrent renewal. */
  suspend fun invalidate(lease: TokenLease) {
    safe {
      exclusive(lease.key) {
        val current = store.load(lease.key)
        if (current?.accessToken ==
          lease.tokens.accessToken
        ) {
          store.save(lease.key, current.copy(expiresAt = Instant.EPOCH))
        }
      }
    }
  }

  /** Cancels this manager's acquisition jobs without canceling the application's parent scope. */
  override fun close() {
    scope.cancel()
  }

  private fun resolve(binding: SecurityBinding): Resolved {
    try {
      val provider = providers[binding.provider] ?: throw TokenProviderException()
      val configuration = provider.configure(binding)
      if (provider.identity.isBlank() ||
        configuration.clientIdentity.isBlank() ||
        (binding.flow == SecurityBinding.Flow.AuthorizationCode && configuration.grantIdentity.isNullOrBlank())
      ) {
        throw TokenProviderException()
      }
      val selected =
        binding.copy(
          scopes = binding.scopes.toSortedSet().toSet(),
          endpoints = binding.endpoints.overriddenBy(configuration.endpoints),
        )
      val request = TokenRequest(selected, configuration.clientIdentity, configuration.grantIdentity)
      val key =
        mapper.writeValueAsString(
          listOf(
            selected.scheme,
            binding.provider,
            provider.identity,
            request.clientIdentity,
            request.grantIdentity,
            selected.profile,
            selected.flow.name,
            selected.endpoints.discoveryUrl,
            selected.endpoints.authorizationUrl,
            selected.endpoints.tokenUrl,
            selected.endpoints.refreshUrl,
            selected.scopes,
            selected.audience,
            selected.resource,
          ),
        )
      return Resolved(provider, request, key)
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      throw TokenProviderException()
    }
  }

  private suspend fun renew(
    key: String,
    provider: TokenProvider,
    request: TokenRequest,
  ): TokenLease =
    safe {
      exclusive(key) {
        val stored = store.load(key)
        if (stored != null && (stored.expiresAt == null || stored.expiresAt > clock.instant().plus(expirySkew))) {
          return@exclusive TokenLease(key, stored)
        }
        val tokens =
          if (stored?.refreshToken != null && provider is TokenProvider.Refreshing) {
            try {
              provider.refresh(request, stored.refreshToken).let {
                it.copy(refreshToken = it.refreshToken ?: stored.refreshToken)
              }
            } catch (error: TokenProviderException) {
              if (error.reason != TokenProviderException.Reason.InvalidGrant ||
                request.binding.flow != SecurityBinding.Flow.ClientCredentials
              ) {
                throw error
              }
              currentCoroutineContext().ensureActive()
              store.remove(key)
              provider.acquire(request)
            }
          } else if (request.binding.flow == SecurityBinding.Flow.AuthorizationCode &&
            (stored != null || key in authorizationAttempts)
          ) {
            throw AuthorizationRequiredException()
          } else {
            if (request.binding.flow == SecurityBinding.Flow.AuthorizationCode) authorizationAttempts.add(key)
            provider.acquire(request)
          }
        if (tokens.accessToken.isEmpty() || tokens.expiresAt?.let { it <= clock.instant() } == true) {
          throw TokenProviderException()
        }
        val renewalJob = currentCoroutineContext()[Job]
        currentCoroutineContext().ensureActive()
        synchronized(guard) {
          val renewal = renewals[key]
          if (renewal == null || renewal.task !== renewalJob || renewal.waiters == 0) throw CancellationException()
          renewal.committing = true
        }
        withContext(NonCancellable) { store.save(key, tokens) }
        TokenLease(key, tokens)
      }
    }

  private suspend fun <T> exclusive(
    key: String,
    action: suspend () -> T,
  ): T {
    val entry = synchronized(guard) { locks.getOrPut(key) { KeyLock() }.also { it.users++ } }
    try {
      return entry.mutex.withLock { action() }
    } finally {
      synchronized(guard) { if (--entry.users == 0) locks.remove(key) }
    }
  }

  private suspend fun <T> safe(action: suspend () -> T): T =
    try {
      action()
    } catch (error: CancellationException) {
      throw error
    } catch (error: AuthorizationRequiredException) {
      throw error
    } catch (error: TokenProviderException) {
      throw error
    } catch (_: Exception) {
      throw TokenProviderException()
    }

  private data class Resolved(
    val provider: TokenProvider,
    val request: TokenRequest,
    val key: String,
  )

  private class Renewal {
    lateinit var task: Deferred<TokenLease>
    var waiters = 0
    var committing = false
  }

  private class KeyLock {
    val mutex = Mutex()
    var users = 0
  }

  private class MemoryTokenStore : TokenStore {
    private val tokens = ConcurrentHashMap<String, TokenSet>()

    override suspend fun load(key: String): TokenSet? = tokens[key]

    override suspend fun save(
      key: String,
      tokens: TokenSet,
    ) {
      this.tokens[key] = tokens
    }

    override suspend fun remove(key: String) {
      tokens.remove(key)
    }
  }
}
