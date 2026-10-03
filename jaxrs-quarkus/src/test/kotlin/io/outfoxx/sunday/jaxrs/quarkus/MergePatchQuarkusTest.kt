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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import io.outfoxx.sunday.json.patch.PatchOp
import io.outfoxx.sunday.json.patch.UpdateOp
import io.outfoxx.sunday.validation.jakarta.ClientModelValidation
import io.outfoxx.sunday.validation.jakarta.ModelMode
import io.outfoxx.sunday.validation.jakarta.Schema
import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.groups.ConvertGroup
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the same Jackson and validation boundaries used by generated merge-patch entities. */
@QuarkusTest
class MergePatchQuarkusTest {
  @TestHTTPResource("/")
  lateinit var baseUri: URI

  @Inject
  lateinit var mapper: ObjectMapper

  @Test
  fun `merge patch rejects required deletion and invalid values before resource invocation`() {
    val client = HttpClient.newHttpClient()

    fun send(json: String): HttpResponse<String> =
      client.send(
        HttpRequest
          .newBuilder(baseUri.resolve("merge-patch"))
          .header("Content-Type", "application/merge-patch+json")
          .PUT(HttpRequest.BodyPublishers.ofString(json))
          .build(),
        HttpResponse.BodyHandlers.ofString(),
      )

    fun count(): Int =
      client
        .send(
          HttpRequest.newBuilder(baseUri.resolve("merge-patch/count")).build(),
          HttpResponse.BodyHandlers.ofString(),
        ).body()
        .toInt()

    val before = count()
    for (json in listOf(
      "{}",
      """{"optional":null,"optionalNullable":null}""",
      """{"required":"value","requiredNullable":"value"}""",
    )) {
      val response = send(json)
      assertEquals(200, response.statusCode(), response.body())
      assertEquals(mapper.readTree(json), mapper.readTree(response.body()))
    }
    assertEquals(before + 3, count())
    for (json in listOf(
      """{"required":null}""",
      """{"requiredNullable":null}""",
      """{"required":"x"}""",
      """{"optional":"x"}""",
    )) {
      val response = send(json)
      assertEquals(400, response.statusCode(), response.body())
    }
    assertEquals(before + 3, count())
  }

  @Test
  fun `client validates mutable patch values and omits cancelled updates on each send`() {
    RestClientBuilder
      .newBuilder()
      .baseUri(baseUri)
      .register(ClientModelValidation::class.java)
      .build(MergePatchClient::class.java)
      .use { client ->
        val patch = MergePatchEntity().apply { optional = PatchOp.set("valid") }
        assertEquals(PatchOp.set("valid"), client.send(patch).optional)
        patch.optional = PatchOp.set("x")
        val error = assertThrows(RuntimeException::class.java) { client.send(patch) }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is ConstraintViolationException })
        patch.optional = PatchOp.delete()
        assertEquals(PatchOp.delete<String>(), client.send(patch).optional)
        patch.optional = PatchOp.none()
        assertEquals(PatchOp.none<String>(), client.send(patch).optional)
      }
  }
}

/** Nullability of the resource value does not grant deletion of a required resource member. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
class MergePatchEntity {
  @get:Schema(requiredValue = true, minLength = 2)
  var required: UpdateOp<String> = PatchOp.none()

  @get:Schema(requiredValue = true, minLength = 2)
  var requiredNullable: UpdateOp<String> = PatchOp.none()

  @get:Schema(minLength = 2)
  var optional: PatchOp<String> = PatchOp.none()

  @get:Schema(minLength = 2)
  var optionalNullable: PatchOp<String> = PatchOp.none()
}

/** Echoes patches only after framework decoding and request validation succeed. */
@Path("merge-patch")
@Consumes("application/merge-patch+json")
@Produces(MediaType.APPLICATION_JSON)
class MergePatchResource {
  private val calls = AtomicInteger()

  @PUT
  fun apply(
    @Valid @ConvertGroup(to = ModelMode.Request::class) entity: MergePatchEntity,
  ): MergePatchEntity {
    calls.incrementAndGet()
    return entity
  }

  @GET
  @Path("count")
  @Produces(MediaType.TEXT_PLAIN)
  fun count(): Int = calls.get()
}

/** Managed client using the same merge-patch media type as the server. */
@Path("merge-patch")
@Consumes("application/merge-patch+json")
@Produces(MediaType.APPLICATION_JSON)
interface MergePatchClient : AutoCloseable {
  @PUT
  fun send(entity: MergePatchEntity): MergePatchEntity
}
