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

import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.http.HeaderParameters
import io.outfoxx.sunday.http.getAll
import io.outfoxx.sunday.http.getFirst
import io.outfoxx.sunday.http.toMultiMap
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isContainedIn
import strikt.assertions.isEqualTo

class HeaderExtensionsTest {

  @Test
  fun `test getFirst`() {
    val headers = HeaderParameters.encode(mapOf("test" to arrayOf(MediaType.JSON, MediaType.CBOR)))

    expectThat(headers.getFirst("test"))
      .isContainedIn(listOf(MediaType.JSON.value, MediaType.CBOR.value))
  }

  @Test
  fun `test getAll`() {
    val headers = HeaderParameters.encode(mapOf("test" to arrayOf(MediaType.JSON, MediaType.CBOR)))

    expectThat(headers.getAll("test"))
      .containsExactlyInAnyOrder(MediaType.JSON.value, MediaType.CBOR.value)
  }

  @Test
  fun `test toMultiMap`() {
    val headers = HeaderParameters.encode(mapOf("test" to arrayOf(MediaType.JSON, MediaType.CBOR)))

    expectThat(headers.toMultiMap())
      .isEqualTo(mapOf("test" to listOf(MediaType.JSON.value, MediaType.CBOR.value)))
  }

}
