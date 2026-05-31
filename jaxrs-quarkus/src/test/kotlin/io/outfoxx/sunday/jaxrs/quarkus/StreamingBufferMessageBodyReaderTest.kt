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

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.vertx.mutiny.core.buffer.Buffer
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.Type
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class StreamingBufferMessageBodyReaderTest {

  @Test
  fun `accepts only Mutiny Multi of Vertx Mutiny buffer request bodies`() {
    val reader = StreamingBufferMessageBodyReader()

    assertTrue(reader.isReadable(Multi::class.java, TypeHolder.buffersType, emptyArray(), MediaType.WILDCARD_TYPE))
    assertFalse(reader.isReadable(Multi::class.java, TypeHolder.stringsType, emptyArray(), MediaType.WILDCARD_TYPE))
    assertFalse(reader.isReadable(String::class.java, String::class.java, emptyArray(), MediaType.WILDCARD_TYPE))
  }

  private object TypeHolder {
    val buffersType: Type = TypeHolderFields::class.java.getDeclaredField("buffers").genericType
    val stringsType: Type = TypeHolderFields::class.java.getDeclaredField("strings").genericType
  }

  @Suppress("unused")
  private class TypeHolderFields {
    lateinit var buffers: Multi<Buffer>
    lateinit var strings: Multi<String>
  }
}

@QuarkusTest
class StreamingBufferMessageBodyReaderQuarkusTest {

  @TestHTTPResource("/upload")
  lateinit var uploadUri: URI

  @Test
  fun `streams chunked request bodies into buffer multi`() {
    val client = HttpClient.newHttpClient()
    val request =
      HttpRequest
        .newBuilder(uploadUri)
        .header("Content-Type", MediaType.APPLICATION_OCTET_STREAM)
        .POST(
          HttpRequest.BodyPublishers.ofInputStream {
            ByteArrayInputStream("alpha-beta-gamma".toByteArray())
          },
        ).build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    assertEquals(200, response.statusCode())
    assertEquals("alpha-beta-gamma", response.body())
  }
}

@Path("/upload")
class StreamingUploadResource {

  @POST
  @Consumes(MediaType.APPLICATION_OCTET_STREAM)
  @Produces(MediaType.TEXT_PLAIN)
  fun upload(body: Multi<Buffer>): Uni<String> =
    body
      .onItem()
      .transform { buffer -> buffer.toString() }
      .collect()
      .asList()
      .map { chunks -> chunks.joinToString("") }
}
