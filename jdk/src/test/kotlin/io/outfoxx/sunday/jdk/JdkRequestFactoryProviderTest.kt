package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.PathEncoder
import io.outfoxx.sunday.RequestFactoryConfig
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.problems.SundayHttpProblem
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class JdkRequestFactoryProviderTest {

  @Test
  fun `provider wires config`() {
    val encoders = MediaTypeEncoders.Builder().build()
    val decoders = MediaTypeDecoders.Builder().build()
    val pathEncoders: Map<kotlin.reflect.KClass<*>, PathEncoder> =
      mapOf(String::class to { value: Any -> value.toString() })
    val config =
      RequestFactoryConfig(
        baseURI = URITemplate("http://example.com"),
        problemFactory = SundayHttpProblem.Factory,
        mediaTypeEncoders = encoders,
        mediaTypeDecoders = decoders,
        pathEncoders = pathEncoders,
      )

    val factory = JdkRequestFactoryProvider().create(config)

    expectThat(factory.mediaTypeEncoders).isEqualTo(encoders)
    expectThat(factory.mediaTypeDecoders).isEqualTo(decoders)
    expectThat(factory.pathEncoders).isEqualTo(pathEncoders)
    expectThat(factory.problemFactory).isEqualTo(SundayHttpProblem.Factory)
  }
}
