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
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.test.Implementation
import io.outfoxx.sunday.test.RequestFactoryTest
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers

class JdkRequestFactoryTest : RequestFactoryTest() {

  override val implementation = Implementation.JDK

  override fun createRequestFactory(
    uriTemplate: URITemplate,
    encoders: MediaTypeEncoders,
    decoders: MediaTypeDecoders
  ): RequestFactory =
    JdkRequestFactory(
      uriTemplate,
      mediaTypeEncoders = encoders,
      mediaTypeDecoders = decoders,
    )

  @Test
  fun `adapt a an HTTP request`() = runBlocking {

    val factory =
      JdkRequestFactory(
        URITemplate("http://example.com"),
        adapter = { request ->
          request.copyToBuilder()
            .header("Authorization", "Bearer 12345")
            .build()
        }
      )

    val request = factory.request(Method.Get, "test")

    assertThat(request.headers, hasItem("Authorization" to "Bearer 12345"))
  }

  @Test
  fun `copying requests to builder`() = runBlocking {

    val get =
      HttpRequest.newBuilder(URI("http://example.com"))
        .GET()
        .build()
    val getCopy = assertDoesNotThrow { get.copyToBuilder().build() }
    assertThat(getCopy.uri(), equalTo(URI("http://example.com")))
    assertThat(getCopy.method(), equalTo("GET"))
    assertThat(getCopy.bodyPublisher().isPresent, equalTo(false))

    val delete =
      HttpRequest.newBuilder(URI("http://example.com"))
        .DELETE()
        .build()
    val deleteCopy = assertDoesNotThrow { delete.copyToBuilder().build() }
    assertThat(deleteCopy.uri(), equalTo(URI("http://example.com")))
    assertThat(deleteCopy.method(), equalTo("DELETE"))
    assertThat(deleteCopy.bodyPublisher().isPresent, equalTo(false))

    val post =
      HttpRequest.newBuilder(URI("http://example.com"))
        .POST(BodyPublishers.noBody())
        .build()
    val postCopy = assertDoesNotThrow { post.copyToBuilder().build() }
    assertThat(postCopy.uri(), equalTo(URI("http://example.com")))
    assertThat(postCopy.method(), equalTo("POST"))
    assertThat(postCopy.bodyPublisher().isPresent, equalTo(true))

    val put =
      HttpRequest.newBuilder(URI("http://example.com"))
        .PUT(BodyPublishers.ofString("test"))
        .build()
    val putCopy = assertDoesNotThrow { put.copyToBuilder().build() }
    assertThat(putCopy.uri(), equalTo(URI("http://example.com")))
    assertThat(putCopy.method(), equalTo("PUT"))
    assertThat(putCopy.bodyPublisher().isPresent, equalTo(true))

    val custom =
      HttpRequest.newBuilder(URI("http://example.com"))
        .method("TEST", BodyPublishers.noBody())
        .build()
    val customCopy = assertDoesNotThrow { custom.copyToBuilder().build() }
    assertThat(customCopy.uri(), equalTo(URI("http://example.com")))
    assertThat(customCopy.method(), equalTo("TEST"))
    assertThat(customCopy.bodyPublisher().isPresent, equalTo(true))
  }
}
