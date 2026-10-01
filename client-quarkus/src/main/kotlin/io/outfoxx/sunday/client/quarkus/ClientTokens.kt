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

import io.quarkus.oidc.client.OidcClients
import io.quarkus.oidc.client.Tokens
import io.quarkus.oidc.client.runtime.TokensHelper
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Shares native acquisition and expiry renewal across operations selecting the same isolated client. */
@ApplicationScoped
class ClientTokens {
  @Inject
  internal lateinit var clients: OidcClients

  private val entries = ConcurrentHashMap<String, Entry>()

  /** Renews a rejected credential once; late responses cannot invalidate a newer native token lease. */
  fun acquire(
    name: String,
    rejected: Tokens? = null,
  ): Uni<Tokens> =
    Uni.createFrom().deferred {
      val entry = entries.computeIfAbsent(name) { Entry() }
      val client = requireNotNull(clients.getClient(name)) { "Missing named OIDC client: $name" }
      entry.helper.getTokens(client).flatMap { current ->
        synchronized(entry) {
          when {
            current !== rejected -> Uni.createFrom().item(current)
            !entry.invalidated.add(current) -> entry.helper.getTokens(client)
            else -> entry.helper.getTokens(client, emptyMap(), true)
          }
        }
      }
    }

  private class Entry {
    val helper = TokensHelper()

    // Retain no credentials once their invocations and the native cache release them.
    val invalidated: MutableSet<Tokens> = Collections.newSetFromMap(WeakHashMap())
  }
}
