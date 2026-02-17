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
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.Method
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class JdkRequestBodyTest {

  @Test
  fun `request bodies can be read`() =
    runTest {
      val factory = JdkRequestFactory(URITemplate("http://example.com"))

      val request =
        factory.request(
          Method.Post,
          "/body",
          body = mapOf("a" to 1),
          contentTypes = listOf(MediaType.JSON),
        )

      val body = request.body()

      expectThat(body?.readByteArray()).isEqualTo("""{"a":1}""".encodeToByteArray())
    }
}
