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

import io.quarkus.oidc.client.OidcClientException
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Exercises native OIDC failures through an actual Quarkus REST client and both Sunday filters. */
@QuarkusTest
@QuarkusTestResource(AuthenticationTestResource::class)
class ClientAuthenticationQuarkusTest {
  @Inject
  lateinit var filter: ClientAuthenticationFilter

  lateinit var tokenServer: MockWebServer
  lateinit var serviceServer: MockWebServer

  @Test
  fun `initial rejected credentials preserve the OIDC failure without sending an unsafe operation`() {
    tokenServer.enqueue(rejected(401))
    withClient { client -> assertTokenFailure { client.initial(it) } }
  }

  @Test
  fun `expired cached token preserves a rejected native refresh`() {
    verifyExpiredToken(true) { client, attempt -> client.refresh(attempt) }
  }

  @Test
  fun `expired cached token preserves rejected reacquisition without a refresh token`() {
    verifyExpiredToken(false) { client, attempt -> client.reacquire(attempt) }
  }

  @Test
  fun `missing token endpoint preserves the OIDC failure`() {
    tokenServer.enqueue(rejected(404))
    withClient { client -> assertTokenFailure { client.missing(it) } }
  }

  @Test
  fun `token connection failure is not masked or retried as a challenge`() {
    tokenServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    withClient { client -> assertTokenFailure(message = "OIDC Server is not available") { client.network(it) } }
  }

  @Test
  fun `service connection failure after acquisition retains its native cause`() {
    tokenServer.enqueue(token())
    serviceServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    val tokensBefore = tokenServer.requestCount
    val serviceBefore = serviceServer.requestCount
    withClient { client ->
      val failure = assertThrows(RuntimeException::class.java) { invoke { client.transport(it) } }
      val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
      assertFalse(causes.any { it is NullPointerException }, causes.toString())
      assertTrue(causes.any { it is IOException }, causes.toString())
    }
    assertEquals(tokensBefore + 1, tokenServer.requestCount)
    assertEquals(serviceBefore + 1, serviceServer.requestCount)
    assertEquals("Bearer access", serviceServer.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    tokenServer.takeRequest(5, TimeUnit.SECONDS)!!
  }

  @Test
  fun `a separately configured valid client succeeds after rejected acquisition`() {
    tokenServer.enqueue(rejected(401))
    withClient { client -> assertTokenFailure { client.initial(it) } }
    tokenServer.enqueue(token())
    serviceServer.enqueue(MockResponse().setBody("ok"))
    withClient { client -> assertEquals("ok", invoke { client.valid(it) }) }
    assertEquals("Bearer access", serviceServer.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    tokenServer.takeRequest(5, TimeUnit.SECONDS)!!
  }

  private fun verifyExpiredToken(
    refresh: Boolean,
    action: (AuthenticatedClient, ClientInvocation.Attempt) -> String,
  ) {
    tokenServer.enqueue(token(expired = true, refresh = refresh))
    serviceServer.enqueue(MockResponse().setBody("ok"))
    withClient { client ->
      assertEquals("ok", invoke { action(client, it) })
      assertEquals("Bearer access", serviceServer.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
      assertTrue(
        tokenServer
          .takeRequest(5, TimeUnit.SECONDS)!!
          .body
          .readUtf8()
          .contains("grant_type=client_credentials"),
      )
      tokenServer.enqueue(rejected(401))
      assertTokenFailure(if (refresh) "refresh_token" else "client_credentials") { action(client, it) }
    }
  }

  private fun assertTokenFailure(
    grant: String = "client_credentials",
    message: String = "invalid_client",
    action: (ClientInvocation.Attempt) -> String,
  ) {
    val tokensBefore = tokenServer.requestCount
    val serviceBefore = serviceServer.requestCount
    val failure = assertThrows(RuntimeException::class.java) { invoke(action) }
    val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
    assertFalse(causes.any { it is NullPointerException || it is ClientInvocation.Recoverable }, causes.toString())
    assertTrue(
      causes.any { it is OidcClientException && it.message.orEmpty().contains(message) },
      causes.toString(),
    )
    assertEquals(tokensBefore + 1, tokenServer.requestCount)
    assertEquals(serviceBefore, serviceServer.requestCount)
    assertTrue(
      tokenServer
        .takeRequest(5, TimeUnit.SECONDS)!!
        .body
        .readUtf8()
        .contains("grant_type=$grant"),
    )
  }

  private fun invoke(action: (ClientInvocation.Attempt) -> String): String =
    ClientInvocation.execute(true) { invocation ->
      try {
        invocation.attempt(action)
      } catch (_: ClientInvocation.Recoverable) {
        invocation.attempt(action)
      }
    }

  private fun withClient(action: (AuthenticatedClient) -> Unit) {
    RestClientBuilder
      .newBuilder()
      .baseUri(serviceServer.url("/").toUri())
      .register(filter)
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(5, TimeUnit.SECONDS)
      .build(AuthenticatedClient::class.java)
      .use(action)
  }

  private fun rejected(status: Int): MockResponse =
    MockResponse()
      .setResponseCode(status)
      .setHeader("Content-Type", "application/json")
      .setBody("""{"error":"invalid_client"}""")

  private fun token(
    expired: Boolean = false,
    refresh: Boolean = false,
  ): MockResponse {
    val refreshField = if (refresh) """, "refresh_token":"refresh", "refresh_expires_in":3600""" else ""
    return MockResponse()
      .setHeader("Content-Type", "application/json")
      .setBody("""{"access_token":"access","expires_in":${if (expired) -1 else 3600}$refreshField}""")
  }

  /** Mirrors generated transport methods carrying the invocation attempt as a method parameter. */
  @Path("/service")
  interface AuthenticatedClient : AutoCloseable {
    /** Sends an unsafe operation only after successful initial acquisition. */
    @POST
    @ClientAuthentication("initial")
    fun initial(
      @QueryParam("attempt") attempt: ClientInvocation.Attempt,
    ): String

    /** Acquires and refreshes a cached token. */
    @GET
    @ClientAuthentication("refresh")
    fun refresh(
      @QueryParam("attempt") attempt: ClientInvocation.Attempt,
    ): String

    /** Reacquires an expired token without a refresh token. */
    @GET
    @ClientAuthentication("reacquire")
    fun reacquire(
      @QueryParam("attempt") attempt: ClientInvocation.Attempt,
    ): String

    /** Uses a missing token endpoint. */
    @GET
    @ClientAuthentication("missing")
    fun missing(
      @QueryParam("attempt") attempt: ClientInvocation.Attempt,
    ): String

    /** Uses a token endpoint that closes the connection. */
    @GET
    @ClientAuthentication("network")
    fun network(
      @QueryParam("attempt") attempt: ClientInvocation.Attempt,
    ): String

    /** Uses a separately configured working client. */
    @GET
    @ClientAuthentication("valid")
    fun valid(
      @QueryParam("attempt") attempt: ClientInvocation.Attempt,
    ): String

    /** Acquires a token before the service connection fails. */
    @GET
    @ClientAuthentication("transport")
    fun transport(
      @QueryParam("attempt") attempt: ClientInvocation.Attempt,
    ): String
  }
}
