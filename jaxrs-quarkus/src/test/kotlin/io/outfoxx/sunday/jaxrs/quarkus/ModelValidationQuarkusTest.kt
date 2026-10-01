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

package io.outfoxx.sunday.jaxrs.quarkus

import io.outfoxx.sunday.validation.jakarta.ClientModelValidation
import io.outfoxx.sunday.validation.jakarta.KnownVariant
import io.outfoxx.sunday.validation.jakarta.ModelMode
import io.outfoxx.sunday.validation.jakarta.ModelValidation
import io.outfoxx.sunday.validation.jakarta.Schema
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.Validator
import jakarta.validation.groups.ConvertGroup
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger

@QuarkusTest
class ModelValidationQuarkusTest {

  @TestHTTPResource("/")
  lateinit var baseUri: URI

  @Inject
  lateinit var validator: Validator

  @Test
  fun `managed validation rejects invalid entities before resource invocation`() {
    assertSame(validator, ModelValidation.validatorProvider())
    assertThrows(ConstraintViolationException::class.java) { ConstructedPayload("x") }
    ConstructedPayload("valid")
    val client = HttpClient.newHttpClient()

    fun send(body: String): Int =
      client
        .send(
          HttpRequest
            .newBuilder(baseUri.resolve("validation"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
          HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

    fun count(): Int =
      client
        .send(
          HttpRequest.newBuilder(baseUri.resolve("validation/count")).build(),
          HttpResponse.BodyHandlers.ofString(),
        ).body()
        .toInt()
    val before = count()
    assertEquals(400, send("""{"text":"x"}"""))
    assertEquals(400, send("""{"text":"valid","state":{"raw":"active"}}"""))
    assertEquals(before, count())
    assertEquals(200, send("""{"text":"valid"}"""))
    assertEquals(before + 1, count())
  }

  @Test
  fun `registered client validates immediately before encoding on every call`() {
    RestClientBuilder
      .newBuilder()
      .baseUri(baseUri)
      .register(ClientModelValidation::class.java)
      .build(ValidationClient::class.java)
      .use { client ->
        val payload = ValidationPayload().apply { text = "valid" }
        assertEquals("valid", client.send(payload))
        payload.text = "x"
        val error = assertThrows(RuntimeException::class.java) { client.send(payload) }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is ConstraintViolationException })
        payload.text = "valid"
        assertEquals("valid", client.send(payload))
        assertEquals("future", client.unknown().raw)
      }
  }

}

class ConstructedPayload(
  @param:Schema(minLength = 2)
  @get:Schema(minLength = 2)
  val text: String,
) {
  init {
    ModelValidation.constructor(ConstructedPayload::class.java, arrayOf(String::class.java), text)
  }
}

class ValidationPayload {
  @get:Schema(minLength = 2)
  var text: String = ""

  @get:Valid
  var state: UnknownState? = null
}

@KnownVariant(groups = [ModelMode.Request::class])
class UnknownState {
  var raw: String = "future"
}

@Path("validation")
@Consumes(MediaType.APPLICATION_JSON)
class ValidationResource {
  private val calls = AtomicInteger()

  @POST
  @Produces(MediaType.TEXT_PLAIN)
  fun send(
    @Valid @ConvertGroup(to = ModelMode.Request::class) payload: ValidationPayload,
  ): String {
    calls.incrementAndGet()
    return payload.text
  }

  @GET
  @Path("count")
  @Produces(MediaType.TEXT_PLAIN)
  fun count(): Int = calls.get()

  @GET
  @Path("unknown")
  @Produces(MediaType.APPLICATION_JSON)
  fun unknown(): UnknownState = UnknownState()
}

@Path("validation")
@Consumes(MediaType.APPLICATION_JSON)
interface ValidationClient : AutoCloseable {
  @POST
  @Produces(MediaType.TEXT_PLAIN)
  fun send(payload: ValidationPayload): String

  @GET
  @Path("unknown")
  @Produces(MediaType.APPLICATION_JSON)
  fun unknown(): UnknownState
}
