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

import io.outfoxx.sunday.mediatypes.codecs.BinaryDecoder
import io.outfoxx.sunday.mediatypes.codecs.BinaryEncoder
import io.outfoxx.sunday.mediatypes.codecs.TextDecoder
import io.outfoxx.sunday.mediatypes.codecs.TextEncoder
import io.outfoxx.sunday.mediatypes.codecs.decode
import io.outfoxx.sunday.utils.buffer
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.Source
import okio.buffer
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

      assertThat(decoder.decode<String>("testing".buffer()), equalTo("testing"))
      assertThat(decoder.decode<CharSequence>("testing".buffer()), equalTo("testing"))
    }

    @Test
    fun `test decoder fails to decode non text`() {

      val decoder = TextDecoder.default

      assertThrows<IllegalArgumentException> {
        decoder.decode<Int>("testing".buffer())
      }
    }

    @Test
    fun `test encoder encodes text`() {

      val encoder = TextEncoder.default

      assertThat(encoder.encode("testing"), equalTo("testing".buffer()))
      assertThat(encoder.encode(StringBuilder("testing")), equalTo("testing".buffer()))
    }

    @Test
    fun `test encoder fails to encode non text values`() {

      val encoder = TextEncoder.default

      assertThrows<IllegalArgumentException> {
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
      assertThat(decoder.decode<ByteArray>(buffer.copy()), equalTo(buffer.copy().readByteArray()))
      assertThat(decoder.decode<ByteString>(buffer.copy()), equalTo(buffer.copy().readByteString()))
      assertThat(
        decoder.decode<InputStream>(buffer.copy()).readAllBytes(),
        equalTo(buffer.copy().readByteArray())
      )
      assertThat(
        decoder.decode<Source>(buffer.copy()).buffer().readByteArray(),
        equalTo(buffer.copy().readByteArray())
      )
      assertThat(
        decoder.decode<BufferedSource>(buffer.copy()).readByteArray(),
        equalTo(buffer.copy().readByteArray())
      )
    }

    @Test
    fun `test decoder fails to decode non binary`() {

      val decoder = BinaryDecoder()

      assertThrows<IllegalArgumentException> {
        decoder.decode("testing".buffer(), typeOf<Int>())
      }
    }

    @Test
    fun `test encoder encodes binary values`() {

      val encoder = BinaryEncoder()

      assertThat(
        encoder.encode(byteArrayOf(1, 2, 3)),
        equalTo(Buffer().write(byteArrayOf(1, 2, 3)))
      )
      assertThat(
        encoder.encode(ByteString.of(1, 2, 3)),
        equalTo(Buffer().write(byteArrayOf(1, 2, 3)))
      )
      assertThat(
        encoder.encode(ByteArrayInputStream(byteArrayOf(1, 2, 3))),
        equalTo(Buffer().write(byteArrayOf(1, 2, 3)))
      )
      assertThat(
        encoder.encode(Buffer().write(byteArrayOf(1, 2, 3))),
        equalTo(Buffer().write(byteArrayOf(1, 2, 3)))
      )
    }

    @Test
    fun `test encoder fails to encode non binary values`() {

      val encoder = BinaryEncoder()

      assertThrows<IllegalArgumentException> {
        encoder.encode(10)
      }
    }
  }

}
