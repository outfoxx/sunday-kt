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

/** OAuth inputs narrowed to a supported acquisition flow. */
sealed interface OAuthCredentials : Credentials.OAuth {
  /** Application client/session configuration; secrets never enter generated metadata. */
  val configuration: OAuthTokenProvider.Configuration

  /** Installed transport module adapter; builds a provider without acquiring tokens. */
  val providerFactory: (OAuthTokenProvider.Configuration) -> TokenProvider

  /** Service-to-service OAuth exchange. */
  class ClientCredentials(
    override val configuration: OAuthTokenProvider.Configuration,
    override val providerFactory: (OAuthTokenProvider.Configuration) -> TokenProvider,
  ) : OAuthCredentials {
    override fun toString(): String = "OAuthCredentials.ClientCredentials()"
  }

  /** Application-authorized authorization-code exchange with PKCE. */
  class AuthorizationCode(
    override val configuration: OAuthTokenProvider.Configuration,
    override val providerFactory: (OAuthTokenProvider.Configuration) -> TokenProvider,
  ) : OAuthCredentials {
    override fun toString(): String = "OAuthCredentials.AuthorizationCode()"
  }
}
