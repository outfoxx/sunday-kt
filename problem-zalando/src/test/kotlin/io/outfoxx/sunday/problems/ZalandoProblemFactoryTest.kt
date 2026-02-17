package io.outfoxx.sunday.problems

import io.outfoxx.sunday.http.Status
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.containsKey
import strikt.assertions.getValue
import strikt.assertions.isEqualTo
import java.net.URI
import org.zalando.problem.Status as ZalandoStatus

class ZalandoProblemFactoryTest {

  @Test
  fun `builder sets fields and adapter reads them`() {
    val type = URI.create("urn:test:type")
    val instance = URI.create("urn:test:instance")

    val problem =
      ZalandoProblemFactory
        .typed(type)
        .detail("Detail")
        .instance(instance)
        .extension("extra", "value")
        .build()

    val adapter = ZalandoProblemFactory.adapter()

    expectThat(adapter.getType(problem)).isEqualTo(type)
    expectThat(adapter.getDetail(problem)).isEqualTo("Detail")
    expectThat(adapter.getInstance(problem)).isEqualTo(instance)
    expectThat(adapter.getExtensions(problem))
      .containsKey("extra")
      .getValue("extra")
      .isEqualTo("value")
  }

  @Test
  fun `standard statuses map to zalando status`() {
    val problem = ZalandoProblemFactory.from(Status.BadRequest).build() as org.zalando.problem.ThrowableProblem

    expectThat(problem.status).isEqualTo(ZalandoStatus.BAD_REQUEST)
  }

  @Test
  fun `non standard reason phrases remain intact`() {
    val problem = ZalandoProblemFactory.from(Status.Unused_418).build() as org.zalando.problem.ThrowableProblem

    expectThat(problem.status?.reasonPhrase).isEqualTo(Status.Unused_418.reasonPhrase)
  }

  @Test
  fun `provider creates zalando factory`() {
    val provider = ZalandoProblemFactoryProvider()

    expectThat(provider.id).isEqualTo("zalando")
    expectThat(provider.priority).isEqualTo(100)
    expectThat(provider.create()).isEqualTo(ZalandoProblemFactory)
  }
}
