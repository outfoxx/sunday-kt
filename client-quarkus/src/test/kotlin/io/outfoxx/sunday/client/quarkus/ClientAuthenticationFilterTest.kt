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

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Proxy

class ClientAuthenticationFilterTest {
  private val filter = ClientAuthenticationFilter()

  @Test
  fun `absent response state preserves the original failure and recovery budget`() {
    val invocation = ClientInvocation(true)
    for (method in listOf("GET", "POST")) {
      val original = IOException("Token endpoint unavailable")
      val observed =
        assertThrows(IOException::class.java) {
          invocation.attempt { attempt ->
            attempt.startRequest(method, method == "POST")
            filter.filter(request(attempt), response(null))
            assertEquals(0, attempt.status)
            assertFalse(attempt.invalidToken)
            throw original
          }
        }
      assertSame(original, observed)
    }
    assertThrows(ClientInvocation.Recoverable::class.java) {
      invocation.attempt { attempt ->
        attempt.startRequest("GET", false)
        filter.filter(request(attempt), response(challenge(), 401))
        throw IOException("Actual invalid token response")
      }
    }
  }

  @Test
  fun `empty headers still classify an actual unauthorized response as terminal`() {
    val invocation = ClientInvocation(true)
    val original = IOException("Unauthorized")
    val stopped =
      assertThrows(ClientInvocation.Stopped::class.java) {
        invocation.attempt { attempt ->
          attempt.startRequest("GET", false)
          filter.filter(request(attempt), response(MultivaluedHashMap(), 401))
          throw original
        }
      }
    assertSame(original, stopped.cause)
  }

  @Test
  fun `real challenges retain case insensitive matching and bounded safe recovery`() {
    val invocation = ClientInvocation(true)
    repeat(2) { index ->
      val expected = if (index == 0) ClientInvocation.Recoverable::class.java else ClientInvocation.Stopped::class.java
      assertThrows(expected) {
        invocation.attempt { attempt ->
          attempt.startRequest("GET", false)
          filter.filter(request(attempt), response(challenge(), 401))
          throw IOException("Invalid token")
        }
      }
    }
    assertThrows(ClientInvocation.Stopped::class.java) { invocation.attempt { error("Unexpected replay") } }
  }

  private fun challenge(): MultivaluedMap<String, String> =
    MultivaluedHashMap<String, String>().apply {
      add("wWw-AuThEnTiCaTe", "Basic realm=service")
      add("wWw-AuThEnTiCaTe", "Bearer error=\"invalid_token\"")
    }

  private fun request(attempt: ClientInvocation.Attempt): ClientRequestContext =
    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ClientRequestContext::class.java)) { _, method, arguments ->
      when (method.name) {
        "getProperty" -> {
          assertEquals("io.quarkus.rest.client.invokedMethodParameters", arguments!![0])
          listOf(attempt)
        }
        else -> error("Unexpected request access: ${method.name}")
      }
    } as ClientRequestContext

  private fun response(
    headers: MultivaluedMap<String, String>?,
    status: Int? = null,
  ): ClientResponseContext =
    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ClientResponseContext::class.java)) { _, method, _ ->
      when (method.name) {
        "getHeaders" -> headers
        "getStatus" -> requireNotNull(status) { "Absent response status must not be read" }
        else -> error("Unexpected response access: ${method.name}")
      }
    } as ClientResponseContext
}
