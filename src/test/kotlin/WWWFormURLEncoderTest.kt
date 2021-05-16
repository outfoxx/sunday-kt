import io.outfoxx.sunday.mediatypes.codecs.WWWFormURLEncoder
import io.outfoxx.sunday.mediatypes.codecs.WWWFormURLEncoder.Companion.encodeURIComponent
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class WWWFormURLEncoderTest {

  @Test
  fun `percent encodes keys`() {
    val encoder = WWWFormURLEncoder(
      WWWFormURLEncoder.ArrayEncoding.Unbracketed,
      WWWFormURLEncoder.BoolEncoding.Numeric,
      WWWFormURLEncoder.DateEncoding.ISO8601
    )

    assertThat(
      encoder.encodeQueryString(mapOf("test/data" to listOf(1, 2, 3))),
      equalTo("test%2Fdata=1&test%2Fdata=2&test%2Fdata=3")
    )
  }

  @Test
  fun `percent encodes values`() {
    val encoder = WWWFormURLEncoder(
      WWWFormURLEncoder.ArrayEncoding.Unbracketed,
      WWWFormURLEncoder.BoolEncoding.Numeric,
      WWWFormURLEncoder.DateEncoding.ISO8601
    )

    assertThat(
      encoder.encodeQueryString(mapOf("test" to listOf("1/1", "1/2", "1/3", " !'()~"))),
      equalTo("test=1%2F1&test=1%2F2&test=1%2F3&test=%20!'()~")
    )
  }

  @Test
  fun `encodes complex values`() {
    val encoder = WWWFormURLEncoder.default

    assertThat(
      encoder.encodeQueryString(mapOf("test" to mapOf("a" to 1, "b" to 2), "c" to "3")),
      equalTo("c=3&test%5Ba%5D=1&test%5Bb%5D=2")
    )
  }

  @Test
  fun `encodes array values in bracketed form`() {
    val encoder = WWWFormURLEncoder(
      WWWFormURLEncoder.ArrayEncoding.Bracketed,
      WWWFormURLEncoder.BoolEncoding.Numeric,
      WWWFormURLEncoder.DateEncoding.ISO8601
    )

    assertThat(
      encoder.encodeQueryString(mapOf("test" to listOf(1, 2, 3))),
      equalTo("test%5B%5D=1&test%5B%5D=2&test%5B%5D=3")
    )
  }

  @Test
  fun `encodes array values in unbracketed form`() {
    val encoder = WWWFormURLEncoder(
      WWWFormURLEncoder.ArrayEncoding.Unbracketed,
      WWWFormURLEncoder.BoolEncoding.Numeric,
      WWWFormURLEncoder.DateEncoding.ISO8601
    )

    assertThat(
      encoder.encodeQueryString(mapOf("test" to listOf(1, 2, 3))),
      equalTo("test=1&test=2&test=3")
    )
  }

  @Test
  fun `encodes bool values in numeric form`() {
    val encoder = WWWFormURLEncoder(
      WWWFormURLEncoder.ArrayEncoding.Unbracketed,
      WWWFormURLEncoder.BoolEncoding.Numeric,
      WWWFormURLEncoder.DateEncoding.ISO8601
    )

    assertThat(
      encoder.encodeQueryString(mapOf("test" to listOf(true, false))),
      equalTo("test=1&test=0")
    )
  }

  @Test
  fun `encodes bool values in literal form`() {
    val encoder = WWWFormURLEncoder(
      WWWFormURLEncoder.ArrayEncoding.Unbracketed,
      WWWFormURLEncoder.BoolEncoding.Literal,
      WWWFormURLEncoder.DateEncoding.ISO8601
    )

    assertThat(
      encoder.encodeQueryString(mapOf("test" to listOf(true, false))),
      equalTo("test=true&test=false")
    )
  }


  val date1 = Instant.parse("2017-05-15T08:30:00Z")
  val date2 = OffsetDateTime.parse("2018-06-16T09:40:10+07:00").toInstant();

  @Test
  fun `encodes date values in ISO form`() {
    val encoder = WWWFormURLEncoder(
      WWWFormURLEncoder.ArrayEncoding.Unbracketed,
      WWWFormURLEncoder.BoolEncoding.Numeric,
      WWWFormURLEncoder.DateEncoding.ISO8601
    )

    assertThat(
      encoder.encodeQueryString(mapOf("test" to listOf(date1, date2))),
      equalTo("test=${encodeURIComponent(date1.toString())}&test=${encodeURIComponent(date2.toString())}")
    )
  }

  @Test
  fun `encodes date values in seconds since epoch form`() {
    val encoder = WWWFormURLEncoder(
      WWWFormURLEncoder.ArrayEncoding.Unbracketed,
      WWWFormURLEncoder.BoolEncoding.Numeric,
      WWWFormURLEncoder.DateEncoding.SecondsSince1970
    )

    assertThat(
      encoder.encodeQueryString(mapOf("test" to listOf(date1, date2))),
      equalTo("test=${encodeURIComponent(date1.toEpochMilli() / 1000.0)}&test=${encodeURIComponent(date2.toEpochMilli() / 1000.0)}")
    )
  }

  @Test
  fun `encodes date values in milliseconds since epoch form`() {
    val encoder = WWWFormURLEncoder(
      WWWFormURLEncoder.ArrayEncoding.Unbracketed,
      WWWFormURLEncoder.BoolEncoding.Numeric,
      WWWFormURLEncoder.DateEncoding.MillisecondsSince1970
    )

    assertThat(
      encoder.encodeQueryString(mapOf("test" to listOf(date1, date2))),
      equalTo("test=${encodeURIComponent(date1.toEpochMilli())}&test=${encodeURIComponent(date2.toEpochMilli())}")
    )
  }

}
