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

package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.RequestFactory
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.Authorization
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.test.Implementation
import io.outfoxx.sunday.test.RequestFactoryTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.isEqualTo
import java.net.URI
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers

class JdkRequestFactoryTest : RequestFactoryTest() {

  override val implementation = Implementation.JDK

  override fun createRequestFactory(
    uriTemplate: URITemplate,
    encoders: MediaTypeEncoders,
    decoders: MediaTypeDecoders,
  ): RequestFactory =
    JdkRequestFactory(
      uriTemplate,
      mediaTypeEncoders = encoders,
      mediaTypeDecoders = decoders,
    )

  @Test
  fun `adapt a an HTTP request`() =
    runTest {
      val factory =
        JdkRequestFactory(
          URITemplate("http://example.com"),
          adapter = { request ->
            request
              .copyToBuilder()
              .header("Authorization", "Bearer 12345")
              .build()
          },
        )

      val request = factory.request(Method.Get, "test")

      expectThat(request.headers).contains("Authorization" to "Bearer 12345")
    }

  @Test
  @Suppress("LongMethod")
  fun `copying requests to builder`() =
    runTest {
      val headers =
        HttpHeaders.of(
          mapOf(
            Authorization to listOf("Bearer 12345"),
            ContentType to listOf("application/json", "application/cbor"),
          ),
        ) { _, _ -> true }

      val get =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .GET()
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val getCopy = get.copyToBuilder().build()
      expectThat(getCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(getCopy.method()).isEqualTo("GET")
      expectThat(getCopy.bodyPublisher().isPresent).isEqualTo(false)
      expectThat(getCopy.headers()).isEqualTo(headers)

      val delete =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .DELETE()
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val deleteCopy = delete.copyToBuilder().build()
      expectThat(deleteCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(deleteCopy.method()).isEqualTo("DELETE")
      expectThat(deleteCopy.bodyPublisher().isPresent).isEqualTo(false)
      expectThat(deleteCopy.headers()).isEqualTo(headers)

      val post =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .POST(BodyPublishers.noBody())
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val postCopy = post.copyToBuilder().build()
      expectThat(postCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(postCopy.method()).isEqualTo("POST")
      expectThat(postCopy.bodyPublisher().isPresent).isEqualTo(true)
      expectThat(postCopy.headers()).isEqualTo(headers)

      val put =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .PUT(BodyPublishers.ofString("test"))
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val putCopy = put.copyToBuilder().build()
      expectThat(putCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(putCopy.method()).isEqualTo("PUT")
      expectThat(putCopy.bodyPublisher().isPresent).isEqualTo(true)
      expectThat(putCopy.headers()).isEqualTo(headers)

      val custom =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .method("TEST", BodyPublishers.noBody())
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val customCopy = custom.copyToBuilder().build()
      expectThat(customCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(customCopy.method()).isEqualTo("TEST")
      expectThat(customCopy.bodyPublisher().isPresent).isEqualTo(true)
      expectThat(customCopy.headers()).isEqualTo(headers)
    }

  @Test
  @Suppress("LongMethod")
  fun `copying requests to builder without headers`() =
    runTest {
      val headers = HttpHeaders.of(mapOf()) { _, _ -> true }

      val get =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .GET()
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val getCopy = get.copyToBuilder(includeHeaders = false).build()
      expectThat(getCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(getCopy.method()).isEqualTo("GET")
      expectThat(getCopy.bodyPublisher().isPresent).isEqualTo(false)
      expectThat(getCopy.headers()).isEqualTo(headers)

      val delete =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .DELETE()
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val deleteCopy = delete.copyToBuilder(includeHeaders = false).build()
      expectThat(deleteCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(deleteCopy.method()).isEqualTo("DELETE")
      expectThat(deleteCopy.bodyPublisher().isPresent).isEqualTo(false)
      expectThat(deleteCopy.headers()).isEqualTo(headers)

      val post =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .POST(BodyPublishers.noBody())
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val postCopy = post.copyToBuilder(includeHeaders = false).build()
      expectThat(postCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(postCopy.method()).isEqualTo("POST")
      expectThat(postCopy.bodyPublisher().isPresent).isEqualTo(true)
      expectThat(postCopy.headers()).isEqualTo(headers)

      val put =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .PUT(BodyPublishers.ofString("test"))
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val putCopy = put.copyToBuilder(includeHeaders = false).build()
      expectThat(putCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(putCopy.method()).isEqualTo("PUT")
      expectThat(putCopy.bodyPublisher().isPresent).isEqualTo(true)
      expectThat(putCopy.headers()).isEqualTo(headers)

      val custom =
        HttpRequest
          .newBuilder(URI("http://example.com"))
          .method("TEST", BodyPublishers.noBody())
          .header(Authorization, "Bearer 12345")
          .header(ContentType, "application/json")
          .header(ContentType, "application/cbor")
          .build()
      val customCopy = custom.copyToBuilder(includeHeaders = false).build()
      expectThat(customCopy.uri()).isEqualTo(URI("http://example.com"))
      expectThat(customCopy.method()).isEqualTo("TEST")
      expectThat(customCopy.bodyPublisher().isPresent).isEqualTo(true)
      expectThat(customCopy.headers()).isEqualTo(headers)
    }
}
