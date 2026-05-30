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

import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.CONTENT_TYPE
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.getFirstOrNull
import io.outfoxx.sunday.problems.SundayHttpProblem
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class JdkRequestBodyTest {

  @Test
  fun `request bodies can be read`() =
    runTest {
      val factory = JdkTransport(URITemplate("http://example.com"), SundayHttpProblem.Factory)

      val request =
        factory.transportRequest(
          Method.Post,
          "/body",
          body = mapOf("a" to 1),
          contentTypes = listOf(MediaType.JSON),
        )

      val body = request.body()

      expectThat(body?.readByteArray()).isEqualTo("""{"a":1}""".encodeToByteArray())
    }

  @Test
  fun `streaming request bodies are opened lazily and replayed`() =
    runTest {
      var contentLength: Long? = null
      val factory =
        JdkTransport(
          URITemplate("http://example.com"),
          SundayHttpProblem.Factory,
          adapter = { request ->
            contentLength = request.bodyPublisher().orElseThrow().contentLength()
            request
          },
        )
      var opened = 0
      val requestBody =
        StreamingBody.source(contentLength = 3) {
          opened += 1
          Buffer().apply { write(byteArrayOf(1, 2, 3)) }
        }

      val request =
        factory.transportRequest(
          Method.Put,
          "/body",
          body = requestBody,
          contentTypes = listOf(MediaType.from("image/png")),
        )

      expectThat(opened).isEqualTo(0)
      expectThat(contentLength).isEqualTo(3)
      expectThat(request.headers.getFirstOrNull(CONTENT_TYPE)).isEqualTo("image/png")
      expectThat(request.body()?.readByteArray()).isEqualTo(byteArrayOf(1, 2, 3))
      expectThat(request.body()?.readByteArray()).isEqualTo(byteArrayOf(1, 2, 3))
      expectThat(opened).isEqualTo(2)
    }
}
