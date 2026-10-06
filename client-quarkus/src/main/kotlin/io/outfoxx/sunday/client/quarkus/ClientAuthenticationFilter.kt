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

package io.outfoxx.sunday.client.quarkus

import io.outfoxx.sunday.security.BearerChallenge
import io.quarkus.oidc.client.Tokens
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientResponseContext
import jakarta.ws.rs.client.ClientResponseFilter
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestContext
import org.jboss.resteasy.reactive.client.spi.ResteasyReactiveClientRequestFilter
import java.lang.reflect.Method

/** Adapts named native OIDC credentials to the invocation's bounded, policy-owned retry. */
@ApplicationScoped
class ClientAuthenticationFilter :
  ResteasyReactiveClientRequestFilter,
  ClientResponseFilter {
  @Inject
  internal lateinit var tokens: ClientTokens

  override fun filter(context: ResteasyReactiveClientRequestContext) {
    val method = context.getProperty("org.eclipse.microprofile.rest.client.invokedMethod") as? Method ?: return
    val binding = method.getAnnotation(ClientAuthentication::class.java) ?: return
    val invocation = requireNotNull(invocation(context)) { "Authenticated transport requires an invocation context" }
    invocation.startRequest(context.method, context.hasEntity())
    require(!context.headers.containsKey("Authorization")) {
      "Authorization is already supplied for an acquired credential"
    }
    val rejected = invocation.rejectedCredential as? Tokens
    context.suspend()
    val acquisition =
      tokens.acquire(binding.value, rejected).subscribe().with({ lease ->
        if (!invocation.acquired(lease)) {
          context.resume(java.util.concurrent.CancellationException("Transport attempt expired"))
          return@with
        }
        context.headers.putSingle("Authorization", "Bearer " + lease.accessToken)
        context.resume()
      }, { error -> context.resume(error) })
    invocation.acquiring(acquisition)
  }

  override fun filter(
    request: ClientRequestContext,
    response: ClientResponseContext,
  ) {
    val invocation = invocation(request) ?: return
    // Quarkus also runs response filters after failures that have no HTTP response.
    val headers = response.headers ?: return
    val challenge =
      headers.entries
        .filter { it.key.equals("WWW-Authenticate", true) }
        .flatMap { it.value }
        .joinToString(",")
    invocation.received(response.status, BearerChallenge.isInvalidToken(challenge))
  }

  private fun invocation(context: ClientRequestContext): ClientInvocation.Attempt? =
    (context.getProperty("io.quarkus.rest.client.invokedMethodParameters") as? List<*>)
      ?.filterIsInstance<ClientInvocation.Attempt>()
      ?.singleOrNull()
}
