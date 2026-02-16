package io.outfoxx.sunday.problems

import io.outfoxx.sunday.problems.ProblemFactory.Descriptor
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.containsKey
import strikt.assertions.getValue
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import strikt.assertions.withNotNull
import java.net.URI

class ZalandoProblemFactoryTest {

  @Test
  fun `builds and adapts zalando problems`() {
    val descriptor =
      Descriptor(
        type = URI.create("urn:test:problem"),
        title = "Test Problem",
        status = 599,
        detail = "Details",
        instance = URI.create("urn:test:instance"),
        extensions =
          mapOf(
            "extra" to "value",
            "count" to 2,
          ),
      )

    val problem = ZalandoProblemFactory.from(descriptor)
    val adapter = ZalandoProblemFactory.adapter()

    expectThat(adapter.getType(problem)).isEqualTo(descriptor.type)
    expectThat(adapter.getTitle(problem)).isEqualTo(descriptor.title)
    expectThat(adapter.getDetail(problem)).isEqualTo(descriptor.detail)
    expectThat(adapter.getInstance(problem)).isEqualTo(descriptor.instance)

    val status = adapter.getStatus(problem)
    expectThat(status)
      .withNotNull {
        get { code }.isEqualTo(599)
        get { reasonPhrase }.isNull()
      }

    expectThat(adapter.getExtensions(problem)) {
      containsKey("extra")
      getValue("extra").isEqualTo("value")
      containsKey("count")
      getValue("count").isEqualTo(2)
    }
  }

}
