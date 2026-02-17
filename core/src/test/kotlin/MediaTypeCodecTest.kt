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

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper
import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.mediatypes.codecs.BinaryDecoder
import io.outfoxx.sunday.mediatypes.codecs.BinaryEncoder
import io.outfoxx.sunday.mediatypes.codecs.JSONDecoder
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.mediatypes.codecs.ObjectMapperEncoder
import io.outfoxx.sunday.mediatypes.codecs.TextDecoder
import io.outfoxx.sunday.mediatypes.codecs.TextEncoder
import io.outfoxx.sunday.mediatypes.codecs.decode
import io.outfoxx.sunday.utils.buffer
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteArray
import kotlinx.io.readByteString
import kotlinx.io.write
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.zalando.problem.Status
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.reflect.typeOf

class MediaTypeCodecTest {

  @Nested
  @DisplayName("text encoding/decoding")
  inner class TextCodec {

    @Test
    fun `test decoder decodes text`() {
      val decoder = TextDecoder.default

      expectThat(decoder.decode<String>("testing".buffer())).isEqualTo("testing")
      expectThat(decoder.decode<CharSequence>("testing".buffer())).isEqualTo("testing")
    }

    @Test
    fun `test decoder fails to decode non text`() {
      val decoder = TextDecoder.default

      expectThrows<IllegalArgumentException> {
        decoder.decode<Int>("testing".buffer())
      }
    }

    @Test
    fun `test encoder encodes text`() {
      val encoder = TextEncoder.default

      expectThat(sourceBytes(encoder.encode("testing"))).isEqualTo("testing".encodeToByteArray())
      expectThat(sourceBytes(encoder.encode(StringBuilder("testing")))).isEqualTo("testing".encodeToByteArray())
    }

    @Test
    fun `test encoder fails to encode non text values`() {
      val encoder = TextEncoder.default

      expectThrows<IllegalArgumentException> {
        encoder.encode(10)
      }
    }
  }

  @Nested
  @DisplayName("binary encoding/decoding")
  inner class BinaryCodec {

    @Test
    fun `test decoder decodes binary values`() {
      val decoder = BinaryDecoder()

      val buffer = "testing".buffer()
      expectThat(decoder.decode<ByteArray>(bufferCopy(buffer)))
        .isEqualTo(bufferCopy(buffer).readByteArray())
      expectThat(decoder.decode<ByteString>(bufferCopy(buffer)))
        .isEqualTo(bufferCopy(buffer).readByteString())
      expectThat(decoder.decode<InputStream>(bufferCopy(buffer)).readAllBytes())
        .isEqualTo(bufferCopy(buffer).readByteArray())
      expectThat(decoder.decode<Source>(bufferCopy(buffer)).readByteArray())
        .isEqualTo(bufferCopy(buffer).readByteArray())
    }

    @Test
    fun `test decoder fails to decode non binary`() {
      val decoder = BinaryDecoder()

      expectThrows<IllegalArgumentException> {
        decoder.decode("testing".buffer(), typeOf<Int>())
      }
    }

    @Test
    fun `test encoder encodes binary values`() {
      val encoder = BinaryEncoder()

      expectThat(sourceBytes(encoder.encode(byteArrayOf(1, 2, 3))))
        .isEqualTo(byteArrayOf(1, 2, 3))
      expectThat(sourceBytes(encoder.encode(ByteString(byteArrayOf(1, 2, 3)))))
        .isEqualTo(byteArrayOf(1, 2, 3))
      expectThat(sourceBytes(encoder.encode(ByteArrayInputStream(byteArrayOf(1, 2, 3)))))
        .isEqualTo(byteArrayOf(1, 2, 3))
      expectThat(sourceBytes(encoder.encode(Buffer().apply { write(byteArrayOf(1, 2, 3)) })))
        .isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `test encoder fails to encode non binary values`() {
      val encoder = BinaryEncoder()

      expectThrows<IllegalArgumentException> {
        encoder.encode(10)
      }
    }
  }

  @Nested
  @DisplayName("json decoding")
  inner class JsonCodec {
    @Test
    fun `test decoder handles numeric and string statuses`() {
      val decoder = JSONDecoder.default

      val numeric = decoder.decode<StatusHolder>("""{"status":400}""", typeOf<StatusHolder>())
      val numericString = decoder.decode<StatusHolder>("""{"status":"404"}""", typeOf<StatusHolder>())
      val nameString = decoder.decode<StatusHolder>("""{"status":"BAD_REQUEST"}""", typeOf<StatusHolder>())

      expectThat(numeric.status).isEqualTo(Status.BAD_REQUEST)
      expectThat(numericString.status).isEqualTo(Status.NOT_FOUND)
      expectThat(nameString.status).isEqualTo(Status.BAD_REQUEST)
    }
  }

  @Test
  fun `test object mapper encoder writes bytes`() {
    val encoder = ObjectMapperEncoder(JsonMapper())

    val bytes = sourceBytes(encoder.encode(mapOf("a" to 1)))

    expectThat(bytes).isEqualTo("""{"a":1}""".encodeToByteArray())
  }

  @Test
  fun `test encoders builder registers specific codecs`() {
    val encoders =
      MediaTypeEncoders
        .Builder()
        .registerJSON(JsonMapper())
        .registerCBOR(CBORMapper())
        .build()

    expectThat(encoders.find(MediaType.JSON)).isNotNull()
    expectThat(encoders.find(MediaType.CBOR)).isNotNull()
  }

  @Test
  fun `test decoders builder registers specific codecs`() {
    val decoders =
      MediaTypeDecoders
        .Builder()
        .registerJSON(JsonMapper())
        .registerCBOR(CBORMapper())
        .build()

    expectThat(decoders.find(MediaType.JSON)).isNotNull()
    expectThat(decoders.find(MediaType.CBOR)).isNotNull()
  }

  private fun bufferCopy(source: Buffer): Buffer = Buffer().also { source.copyTo(it) }

  private fun sourceBytes(source: Source): ByteArray = source.readByteArray()
}

data class StatusHolder(
  val status: Status,
)
