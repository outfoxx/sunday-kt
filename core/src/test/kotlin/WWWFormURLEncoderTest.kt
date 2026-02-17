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

import io.outfoxx.sunday.mediatypes.codecs.WWWFormURLEncoder
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.time.Instant
import java.time.OffsetDateTime

class WWWFormURLEncoderTest {

  @Test
  fun `percent encodes keys`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test/data" to listOf(1, 2, 3))))
      .isEqualTo("test%2Fdata=1&test%2Fdata=2&test%2Fdata=3")
  }

  @Test
  fun `percent encodes values`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to listOf("1/1", "1/2", "1/3", " !'()~"))))
      .isEqualTo("test=1%2F1&test=1%2F2&test=1%2F3&test=%20!'()~")
  }

  @Test
  fun `encodes complex values`() {
    val encoder = WWWFormURLEncoder.default

    expectThat(encoder.encodeQueryString(mapOf("test" to mapOf("a" to 1, "b" to 2), "c" to "3")))
      .isEqualTo("c=3&test%5Ba%5D=1&test%5Bb%5D=2")
  }

  @Test
  fun `encodes list values in bracketed form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Bracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to listOf(1, 2, 3))))
      .isEqualTo("test%5B%5D=1&test%5B%5D=2&test%5B%5D=3")
  }

  @Test
  fun `encodes list values in unbracketed form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to listOf(1, 2, 3))))
      .isEqualTo("test=1&test=2&test=3")
  }

  @Test
  fun `encodes set values in bracketed form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Bracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to setOf(1, 2, 3))))
      .isEqualTo("test%5B%5D=1&test%5B%5D=2&test%5B%5D=3")
  }

  @Test
  fun `encodes set values in unbracketed form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to setOf(1, 2, 3))))
      .isEqualTo("test=1&test=2&test=3")
  }

  @Test
  fun `encodes array values in bracketed form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Bracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to arrayOf(1, 2, 3))))
      .isEqualTo("test%5B%5D=1&test%5B%5D=2&test%5B%5D=3")
  }

  @Test
  fun `encodes array values in unbracketed form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to arrayOf(1, 2, 3))))
      .isEqualTo("test=1&test=2&test=3")
  }

  @Test
  fun `encodes bool values in numeric form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to listOf(true, false))))
      .isEqualTo("test=1&test=0")
  }

  @Test
  fun `encodes bool values in literal form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Literal,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to listOf(true, false))))
      .isEqualTo("test=true&test=false")
  }


  private val date1 = Instant.parse("2017-05-15T08:30:00.123456789Z")
  private val date2 = OffsetDateTime.parse("2018-06-16T09:40:10.123456789+07:00").toInstant()

  @Test
  fun `encodes date values in ISO form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.ISO8601,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to listOf(date1, date2))))
      .isEqualTo("test=2017-05-15T08%3A30%3A00.123456789Z&test=2018-06-16T02%3A40%3A10.123456789Z")
  }

  @Test
  fun `encodes date values in seconds since epoch form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.FractionalSecondsSinceEpoch,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to listOf(date1, date2))))
      .isEqualTo("test=1494837000.1234567&test=1529116810.1234567")
  }

  @Test
  fun `encodes date values in milliseconds since epoch form`() {
    val encoder =
      WWWFormURLEncoder(
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
        WWWFormURLEncoder.BoolEncoding.Numeric,
        WWWFormURLEncoder.DateEncoding.MillisecondsSinceEpoch,
      )

    expectThat(encoder.encodeQueryString(mapOf("test" to listOf(date1, date2))))
      .isEqualTo("test=1494837000123&test=1529116810123")
  }

  @Test
  fun `encodes null values as flags`() {
    val encoder = WWWFormURLEncoder.default

    expectThat(encoder.encodeQueryString(mapOf("flagged" to null)))
      .isEqualTo("flagged")
  }

  @Test
  fun `encodes to byte arrays`() {
    val encoder = WWWFormURLEncoder.default

    expectThat(encoder.encode(mapOf("test" to 10)).readByteArray())
      .isEqualTo("test=10".encodeToByteArray())
  }

}
