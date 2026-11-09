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

/** Receipt used to invalidate only credentials rejected by a server. */
data class TokenLease(
  val key: String,
  val tokens: TokenSet,
  private val rejection: suspend () -> Boolean = { false },
) {
  /** Asks the originating provider to reject this credential without disturbing concurrent replacements. */
  suspend fun invalidate(): Boolean =
    try {
      rejection()
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

  // Recovery closures differ per invocation even when both receipts identify the same cached credential.
  override fun equals(other: Any?): Boolean = other is TokenLease && key == other.key && tokens == other.tokens

  override fun hashCode(): Int = 31 * key.hashCode() + tokens.hashCode()

  override fun toString(): String = "TokenLease()"
}
