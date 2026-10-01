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

/** Application credential provider; coroutine cancellation propagates to acquisition calls. */
interface TokenProvider {
  /** Non-secret identity that changes with application credential configuration. */
  val identity: String

  /** Resolves the application client/session and optional deployment endpoint overrides. */
  fun configure(binding: SecurityBinding): TokenConfiguration

  /** Acquires credentials for a fresh grant or external/static binding. */
  suspend fun acquire(request: TokenRequest): TokenSet

  /** Optional capability for rotating existing refresh credentials. */
  interface Refreshing : TokenProvider {
    /** Exchanges refresh credentials; unusable interactive sessions require fresh application authorization. */
    suspend fun refresh(
      request: TokenRequest,
      refreshToken: String,
    ): TokenSet
  }
}
