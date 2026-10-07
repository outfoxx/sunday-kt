/*
 * Copyright 2026 Outfox, Inc.
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

package io.outfoxx.sunday.jaxrs.quarkus

import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext

/** Application-owned validator shared by independently generated service packages. */
interface ServerSecurityProvider {
  /** Deployment binding identifier referenced by the contract's server provider. */
  val name: String

  /** Resolves required deployment configuration at startup; throws when configuration is incomplete. */
  fun binding(): Binding

  /** Native request context; validators must authenticate the supplied credential independently. */
  data class Request(
    val context: RoutingContext,
    val identityProviderManager: IdentityProviderManager,
  )

  /** Contract metadata, never an implicit source of issuer or audience trust. */
  data class Scheme(
    val name: String,
    val type: String,
    val httpScheme: String?,
    val location: String?,
    val parameterName: String?,
    val bearerFormat: String?,
    val openIdConnectUrl: String?,
    val oauthFlows: Map<String, OAuthFlow>,
  )

  /** Advertised OAuth endpoints and scopes; deployment configuration owns trust and credentials. */
  data class OAuthFlow(
    val authorizationUrl: String?,
    val tokenUrl: String?,
    val refreshUrl: String?,
    val scopes: Map<String, String>,
  )

  /** Validates a single scheme without blocking the event loop or accepting an unrelated identity. */
  fun interface Authenticator {
    /** Returns a trusted identity, or null for missing or invalid credentials. */
    fun authenticate(
      request: Request,
      scheme: Scheme,
      credential: String?,
    ): Uni<SecurityIdentity?>
  }

  /** Validates credentials and optionally extracts the scheme's explicitly declared permissions. */
  data class Binding(
    val authenticator: Authenticator,
    val permissions: ((SecurityIdentity) -> Set<String>)? = null,
  )
}
