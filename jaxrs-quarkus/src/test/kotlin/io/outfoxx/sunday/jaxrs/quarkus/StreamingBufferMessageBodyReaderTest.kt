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
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveResourceInfo
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import java.lang.reflect.Type
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Flow

class StreamingBufferMessageBodyReaderTest {

  @Test
  fun `accepts only Mutiny Multi of Vertx Mutiny buffer request bodies`() {
    val reader = StreamingBufferMessageBodyReader()

    assertTrue(reader.isReadable(Multi::class.java, TypeHolder.buffersType, emptyArray(), MediaType.WILDCARD_TYPE))
    assertTrue(
      reader.isReadable(
        Multi::class.java,
        TypeHolder.buffersType,
        ResteasyReactiveResourceInfo("test", String::class.java, emptyArray(), false, "test"),
        MediaType.WILDCARD_TYPE,
      ),
    )
    assertFalse(reader.isReadable(Multi::class.java, Multi::class.java, emptyArray(), MediaType.WILDCARD_TYPE))
    assertFalse(reader.isReadable(Multi::class.java, TypeHolder.stringsType, emptyArray(), MediaType.WILDCARD_TYPE))
    assertFalse(reader.isReadable(String::class.java, String::class.java, emptyArray(), MediaType.WILDCARD_TYPE))
  }

  @Test
  fun `fails clearly for standard JAX-RS read path`() {
    val reader = StreamingBufferMessageBodyReader()

    assertThrows(UnsupportedOperationException::class.java) {
      reader.readFrom(
        multiBufferType,
        TypeHolder.buffersType,
        emptyArray(),
        MediaType.WILDCARD_TYPE,
        MultivaluedHashMap(),
        ByteArrayInputStream(ByteArray(0)),
      )
    }
  }

  @Test
  fun `fails clearly outside Vertx backed request context`() {
    val reader = StreamingBufferMessageBodyReader()
    val context =
      Proxy.newProxyInstance(
        ServerRequestContext::class.java.classLoader,
        arrayOf(ServerRequestContext::class.java),
      ) { _, method, _ ->
        when (method.name) {
          "toString" -> "TestServerRequestContext"
          else -> error("Unexpected ServerRequestContext method: ${method.name}")
        }
      } as ServerRequestContext

    assertThrows(InternalServerErrorException::class.java) {
      reader.readFrom(
        multiBufferType,
        TypeHolder.buffersType,
        MediaType.WILDCARD_TYPE,
        context,
      )
    }
  }

  @Test
  fun `rejects second subscriber because request bodies are single use`() {
    val publisher = streamingBufferPublisher()
    val firstSubscriber = RecordingSubscriber()
    val secondSubscriber = RecordingSubscriber()

    publisher.subscribe(firstSubscriber)
    publisher.subscribe(secondSubscriber)

    assertTrue(firstSubscriber.subscribed)
    assertTrue(secondSubscriber.subscribed)
    assertTrue(secondSubscriber.error is IllegalStateException)
  }

  @Suppress("UNCHECKED_CAST")
  private val multiBufferType = Multi::class.java as Class<Multi<Buffer>>

  @Suppress("UNCHECKED_CAST")
  private fun streamingBufferPublisher(): Flow.Publisher<Buffer> {
    val publisherClass =
      Class.forName("${StreamingBufferMessageBodyReader::class.java.name}\$StreamingBufferPublisher")
    val constructor =
      publisherClass
        .getDeclaredConstructor(InputStream::class.java)
        .apply { isAccessible = true }

    return constructor.newInstance(ByteArrayInputStream("payload".toByteArray())) as Flow.Publisher<Buffer>
  }

  private class RecordingSubscriber : Flow.Subscriber<Buffer> {

    var subscribed = false
    var error: Throwable? = null

    override fun onSubscribe(subscription: Flow.Subscription) {
      subscribed = true
    }

    override fun onNext(item: Buffer) = Unit

    override fun onError(throwable: Throwable) {
      error = throwable
    }

    override fun onComplete() = Unit
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
    val payload =
      buildString {
        repeat(20_000) { index ->
          append('a' + (index % 26))
        }
      }
    val client = HttpClient.newHttpClient()
    val request =
      HttpRequest
        .newBuilder(uploadUri)
        .header("Content-Type", MediaType.APPLICATION_OCTET_STREAM)
        .POST(
          HttpRequest.BodyPublishers.ofInputStream {
            ByteArrayInputStream(payload.toByteArray())
          },
        ).build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    assertEquals(200, response.statusCode())
    assertEquals(payload, response.body())
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
