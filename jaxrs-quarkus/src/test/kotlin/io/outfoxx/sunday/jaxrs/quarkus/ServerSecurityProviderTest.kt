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

import io.quarkus.security.credential.TokenCredential
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.TokenAuthenticationRequest
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class ServerSecurityProviderTest {

  @Test
  fun `shared provider forwards native request and metadata and retains trusted identity permissions`() {
    val trusted =
      QuarkusSecurityIdentity
        .builder()
        .setPrincipal { "alice" }
        .addAttribute("permissions", setOf("items:read"))
        .build()
    val requests = mutableListOf<TokenAuthenticationRequest>()
    val manager =
      manager { request ->
        requests.add(request)
        Uni.createFrom().item(trusted)
      }
    val context = context()
    val request = ServerSecurityProvider.Request(context, manager)
    val scheme = scheme()
    val provider =
      object : ServerSecurityProvider {
        override val name = "shared"

        override fun binding() =
          ServerSecurityProvider.Binding(
            authenticator =
              ServerSecurityProvider.Authenticator { incoming, metadata, credential ->
                assertSame(context, incoming.context)
                assertSame(manager, incoming.identityProviderManager)
                assertSame(scheme, metadata)
                incoming.identityProviderManager.authenticate(
                  TokenAuthenticationRequest(
                    TokenCredential(requireNotNull(credential), requireNotNull(metadata.httpScheme)),
                  ),
                )
              },
            permissions = { it.getAttribute<Set<String>>("permissions") },
          )
      }

    val binding = provider.binding()
    val result =
      binding.authenticator
        .authenticate(request, scheme, "opaque-token")
        .await()
        .indefinitely()

    assertEquals("shared", provider.name)
    assertSame(trusted, result)
    assertEquals("alice", result!!.principal.name)
    assertEquals(setOf("items:read"), binding.permissions!!(result))
    assertEquals("opaque-token", requests.single().token.token)
    assertEquals("bearer", requests.single().token.type)
    assertEquals("authorization", scheme.name)
    assertEquals("http", scheme.type)
    assertEquals("header", scheme.location)
    assertEquals("Authorization", scheme.parameterName)
    assertEquals("JWT", scheme.bearerFormat)
    assertEquals("https://advertised.example/.well-known/openid-configuration", scheme.openIdConnectUrl)
    val flow = scheme.oauthFlows.getValue("authorizationCode")
    assertEquals("https://advertised.example/authorize", flow.authorizationUrl)
    assertEquals("https://advertised.example/token", flow.tokenUrl)
    assertEquals("https://advertised.example/refresh", flow.refreshUrl)
    assertEquals(mapOf("items:read" to "Read items"), flow.scopes)
  }

  @Test
  fun `binding without permissions supports absent credentials and propagates validation failures`() {
    val failure = SecurityException("Rejected credential")
    var validations = 0
    val request =
      ServerSecurityProvider.Request(
        context(),
        manager {
          validations++
          Uni.createFrom().failure(failure)
        },
      )
    val binding =
      ServerSecurityProvider.Binding(
        ServerSecurityProvider.Authenticator { incoming, _, credential ->
          if (credential == null) {
            Uni.createFrom().nullItem()
          } else {
            incoming.identityProviderManager.authenticate(
              TokenAuthenticationRequest(TokenCredential(credential, "bearer")),
            )
          }
        },
      )

    assertNull(binding.permissions)
    assertNull(
      binding.authenticator
        .authenticate(request, scheme(), null)
        .await()
        .indefinitely(),
    )
    assertEquals(0, validations)
    val actual =
      assertThrows(SecurityException::class.java) {
        binding.authenticator
          .authenticate(request, scheme(), "invalid")
          .await()
          .indefinitely()
      }
    assertSame(failure, actual)
    assertEquals(1, validations)
  }

  private fun scheme() =
    ServerSecurityProvider.Scheme(
      name = "authorization",
      type = "http",
      httpScheme = "bearer",
      location = "header",
      parameterName = "Authorization",
      bearerFormat = "JWT",
      openIdConnectUrl = "https://advertised.example/.well-known/openid-configuration",
      oauthFlows =
        mapOf(
          "authorizationCode" to
            ServerSecurityProvider.OAuthFlow(
              authorizationUrl = "https://advertised.example/authorize",
              tokenUrl = "https://advertised.example/token",
              refreshUrl = "https://advertised.example/refresh",
              scopes = mapOf("items:read" to "Read items"),
            ),
        ),
    )

  private fun context() =
    Proxy.newProxyInstance(
      RoutingContext::class.java.classLoader,
      arrayOf(RoutingContext::class.java),
    ) { _, method, _ -> error("Unexpected context access: ${method.name}") } as RoutingContext

  private fun manager(authenticate: (TokenAuthenticationRequest) -> Uni<SecurityIdentity>): IdentityProviderManager =
    Proxy.newProxyInstance(
      IdentityProviderManager::class.java.classLoader,
      arrayOf(IdentityProviderManager::class.java),
    ) { _, method, args ->
      check(method.name == "authenticate") { "Unexpected manager method: ${method.name}" }
      authenticate(args!![0] as TokenAuthenticationRequest)
    } as IdentityProviderManager
}
