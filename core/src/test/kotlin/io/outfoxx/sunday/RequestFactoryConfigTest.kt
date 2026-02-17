package io.outfoxx.sunday

import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.problems.ProblemAdapter
import io.outfoxx.sunday.problems.ProblemFactory
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.net.URI

class RequestFactoryConfigTest {

  private object TestProblemFactory : ProblemFactory {
    override fun typed(type: URI) = error("unused")

    override fun from(status: Status) = error("unused")

    override fun from(descriptor: ProblemFactory.Descriptor) = error("unused")

    override fun adapter(): ProblemAdapter = error("unused")
  }

  @Test
  fun `defaults are applied`() {
    val config =
      RequestFactoryConfig(
        baseURI = URITemplate("http://example.com"),
        problemFactory = TestProblemFactory,
      )

    expectThat(config.mediaTypeEncoders).isEqualTo(MediaTypeEncoders.default)
    expectThat(config.mediaTypeDecoders).isEqualTo(MediaTypeDecoders.default)
    expectThat(config.pathEncoders).isEqualTo(PathEncoders.default)
  }
}
