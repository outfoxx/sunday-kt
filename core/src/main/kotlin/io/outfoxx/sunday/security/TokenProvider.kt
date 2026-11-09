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

import io.outfoxx.sunday.isRequestCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/** Application credential provider; coroutine cancellation propagates to acquisition calls. */
interface TokenProvider {
  /** Non-secret identity that changes with application credential configuration. */
  val identity: String

  /** Resolves the application client/session and optional deployment endpoint overrides. */
  fun configure(binding: SecurityBinding): TokenConfiguration

  /** Acquires credentials for a fresh grant or external/static binding. */
  suspend fun acquire(request: TokenRequest): TokenSet

  /** Acquires once without adding a cache, retaining the original request for conditional rejection. */
  suspend fun credentials(binding: SecurityBinding): TokenLease {
    try {
      currentCoroutineContext().ensureActive()
      val config = configure(binding)
      if (identity.isBlank() || config.clientIdentity.isBlank()) throw TokenProviderException()
      val request =
        TokenRequest(
          binding.copy(endpoints = binding.endpoints.overriddenBy(config.endpoints), scopes = binding.scopes.toSet()),
          config.clientIdentity,
          config.grantIdentity,
        )
      val tokens = acquire(request)
      currentCoroutineContext().ensureActive()
      if (tokens.accessToken.isEmpty() || tokens.expiresAt?.let { it <= Instant.now() } == true) {
        throw TokenProviderException()
      }
      return TokenLease("", tokens) { reject(request, tokens.accessToken) }
    } catch (error: CancellationException) {
      throw error
    } catch (error: AuthorizationRequiredException) {
      throw error
    } catch (error: TokenProviderException) {
      throw error
    } catch (error: Exception) {
      if (error.isRequestCancelled) throw error
      throw TokenProviderException(cause = error)
    }
  }

  /** Rejects only this credential in its original session. False leaves recovery to the caller. */
  suspend fun reject(
    request: TokenRequest,
    rejectedToken: String,
  ): Boolean = false

  /** Optional capability for rotating existing refresh credentials. */
  interface Refreshing : TokenProvider {
    /** Exchanges refresh credentials; unusable interactive sessions require fresh application authorization. */
    suspend fun refresh(
      request: TokenRequest,
      refreshToken: String,
    ): TokenSet
  }
}
