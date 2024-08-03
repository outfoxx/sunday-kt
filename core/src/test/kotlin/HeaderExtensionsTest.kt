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
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.oneOf
import org.junit.jupiter.api.Test

class HeaderExtensionsTest {

  @Test
  fun `test getFirst`() {
    val headers = HeaderParameters.encode(mapOf("test" to arrayOf(MediaType.JSON, MediaType.CBOR)))

    assertThat(
      headers.getFirst("test"),
      `is`(oneOf(MediaType.JSON.value, MediaType.CBOR.value)),
    )
  }

  @Test
  fun `test getAll`() {
    val headers = HeaderParameters.encode(mapOf("test" to arrayOf(MediaType.JSON, MediaType.CBOR)))

    assertThat(
      headers.getAll("test"),
      containsInAnyOrder(MediaType.JSON.value, MediaType.CBOR.value),
    )
  }

  @Test
  fun `test toMultiMap`() {
    val headers = HeaderParameters.encode(mapOf("test" to arrayOf(MediaType.JSON, MediaType.CBOR)))

    assertThat(
      headers.toMultiMap(),
      equalTo(mapOf("test" to listOf(MediaType.JSON.value, MediaType.CBOR.value))),
    )
  }

}
