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
import io.outfoxx.sunday.SundayError
import io.outfoxx.sunday.SundayError.Reason.InvalidHeaderValue
import io.outfoxx.sunday.http.HeaderParameters
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo

class HeaderParametersTest {

  @Test
  fun `test encodes array values as repeated headers`() {
    val headers = HeaderParameters.encode(mapOf("test" to arrayOf(MediaType.JSON, MediaType.CBOR)))

    expectThat(headers)
      .containsExactlyInAnyOrder("test" to "application/json", "test" to "application/cbor")
  }

  @Test
  fun `test encodes iterables as repeated headers`() {
    val headers = HeaderParameters.encode(mapOf("test" to listOf(MediaType.JSON, MediaType.CBOR)))

    expectThat(headers)
      .containsExactlyInAnyOrder("test" to "application/json", "test" to "application/cbor")
  }

  @Test
  fun `test string encoding`() {
    val headers = HeaderParameters.encode(mapOf("test" to "header"))

    expectThat(headers).containsExactlyInAnyOrder("test" to "header")
  }

  @Test
  fun `test integer encoding`() {
    val headers = HeaderParameters.encode(mapOf("test" to 123456789))

    expectThat(headers).containsExactlyInAnyOrder("test" to "123456789")
  }

  @Test
  fun `test decimal encoding`() {
    val headers = HeaderParameters.encode(mapOf("test" to 12345.6789))

    expectThat(headers).containsExactlyInAnyOrder("test" to "12345.6789")
  }

  @Test
  fun `test null values are ignored`() {
    val headers = HeaderParameters.encode(mapOf("test" to null))

    expectThat(headers.toList()).isEmpty()
  }

  @Test
  fun `test nested null values are ignored`() {
    val headers =
      HeaderParameters.encode(
        mapOf(
          "test1" to listOf<String?>(null),
          "test2" to arrayOf<Int?>(null),
        ),
      )

    expectThat(headers.toList()).isEmpty()
  }

  @Test
  fun `test fails on invalid header values`() {
    val nullError =
      expectThrows<SundayError> {
        HeaderParameters.encode(mapOf("test" to "a${0.toChar()}b"))
      }

    nullError.get { reason }.isEqualTo(InvalidHeaderValue)

    val lineFeedError =
      expectThrows<SundayError> {
        HeaderParameters.encode(mapOf("test" to "a\nb"))
      }

    lineFeedError.get { reason }.isEqualTo(InvalidHeaderValue)

    val carriageReturnError =
      expectThrows<SundayError> {
        HeaderParameters.encode(mapOf("test" to "a\rb"))
      }

    carriageReturnError.get { reason }.isEqualTo(InvalidHeaderValue)

    val nonAsciiReturnError =
      expectThrows<SundayError> {
        HeaderParameters.encode(mapOf("test" to "a\u1234b"))
      }

    nonAsciiReturnError.get { reason }.isEqualTo(InvalidHeaderValue)
  }

}
