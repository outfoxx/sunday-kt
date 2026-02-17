package io.outfoxx.sunday.problems

import io.outfoxx.sunday.http.Status
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.containsKey
import strikt.assertions.getValue
import strikt.assertions.isEqualTo
import java.net.URI

class QuarkusProblemFactoryTest {

  @Test
  fun `builder sets fields and adapter reads them`() {
    val type = URI.create("urn:test:type")
    val instance = URI.create("urn:test:instance")

    val problem =
      QuarkusProblemFactory
        .typed(type)
        .detail("Detail")
        .instance(instance)
        .extension("extra", "value")
        .build()

    val adapter = QuarkusProblemFactory.adapter()

    expectThat(adapter.getType(problem)).isEqualTo(type)
    expectThat(adapter.getDetail(problem)).isEqualTo("Detail")
    expectThat(adapter.getInstance(problem)).isEqualTo(instance)
    expectThat(adapter.getExtensions(problem))
      .containsKey("extra")
      .getValue("extra")
      .isEqualTo("value")
  }

  @Test
  fun `from status sets status code`() {
    val status = Status(499, "Custom")
    val problem = QuarkusProblemFactory.from(status).build()

    expectThat(QuarkusProblemFactory.adapter().getStatus(problem))
      .isEqualTo(Status.valueOf(499))
  }

  @Test
  fun `provider creates quarkus factory`() {
    val provider = QuarkusProblemFactoryProvider()

    expectThat(provider.id).isEqualTo("quarkus")
    expectThat(provider.priority).isEqualTo(100)
    expectThat(provider.create()).isEqualTo(QuarkusProblemFactory)
  }
}
