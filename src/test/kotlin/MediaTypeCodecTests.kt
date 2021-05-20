import io.outfoxx.sunday.mediatypes.codecs.BinaryDecoder
import io.outfoxx.sunday.mediatypes.codecs.BinaryEncoder
import io.outfoxx.sunday.mediatypes.codecs.decode
import io.outfoxx.sunday.mediatypes.codecs.TextDecoder
import io.outfoxx.sunday.mediatypes.codecs.TextEncoder
import io.outfoxx.sunday.typeOf
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.buffer
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.StringBuilder

class MediaTypeCodecTests {

  @Test
  fun `test text decoder decodes text`() {

    val decoder = TextDecoder()

    assertThat(decoder.decode<String>("testing".encodeToByteArray()), equalTo("testing"))
    assertThat(decoder.decode<CharSequence>("testing".encodeToByteArray()), equalTo("testing"))
  }

  @Test
  fun `test text decoder fails to decode non text`() {

    val decoder = TextDecoder()

    assertThrows<IllegalArgumentException> {
      decoder.decode<Int>("testing".encodeToByteArray())
    }
  }

  @Test
  fun `test binary decoder decodes binary values`() {

    val decoder = BinaryDecoder()

    val bytes = "testing".encodeToByteArray()
    assertThat(decoder.decode<ByteArray>(bytes), equalTo(bytes))
    assertThat(decoder.decode<ByteString>(bytes), equalTo(bytes.toByteString(0, bytes.size)))
    assertThat(decoder.decode<InputStream>(bytes).readBytes(), equalTo(bytes))
    assertThat(decoder.decode<Source>(bytes).buffer().readByteArray(), equalTo(bytes))
    assertThat(decoder.decode<BufferedSource>(bytes).readByteArray(), equalTo(bytes))
  }

  @Test
  fun `test binary decoder fails to decode non binary`() {

    val decoder = BinaryDecoder()

    assertThrows<IllegalArgumentException> {
      decoder.decode("testing".encodeToByteArray(), typeOf<Int>())
    }
  }

  @Test
  fun `test text encoder encodes text`() {

    val encoder = TextEncoder()

    assertThat(encoder.encode("testing"), equalTo("testing".encodeToByteArray()))
    assertThat(encoder.encode(StringBuilder("testing")), equalTo("testing".encodeToByteArray()))
  }

  @Test
  fun `test text encoder fails to encode non text values`() {

    val encoder = TextEncoder()

    assertThrows<IllegalArgumentException> {
      encoder.encode(10)
    }
  }

  @Test
  fun `test binary encoder encodes binary values`() {

    val encoder = BinaryEncoder()

    assertThat(encoder.encode(byteArrayOf(1, 2, 3,)), equalTo(byteArrayOf(1, 2, 3,)))
    assertThat(encoder.encode(ByteString.of(1, 2, 3,)), equalTo(byteArrayOf(1, 2, 3,)))
    assertThat(encoder.encode(ByteArrayInputStream(byteArrayOf(1, 2, 3))), equalTo(byteArrayOf(1, 2, 3)))
    val buffer = Buffer()
    buffer.write(byteArrayOf(1, 2, 3))
    assertThat(encoder.encode(buffer), equalTo(byteArrayOf(1, 2, 3)))
  }

  @Test
  fun `test binary encoder fails to encode non binary values`() {

    val encoder = BinaryEncoder()

    assertThrows<IllegalArgumentException> {
      encoder.encode(10)
    }
  }

}
