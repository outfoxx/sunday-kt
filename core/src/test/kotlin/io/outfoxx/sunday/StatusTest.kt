package io.outfoxx.sunday

import io.outfoxx.sunday.http.Status
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull

class StatusTest {

  @Test
  fun `valueOf handles codes and reason phrases`() {
    expectThat(Status.valueOf(200)).isEqualTo(Status.Ok)
    expectThat(Status.valueOf("404")).isEqualTo(Status.NotFound)
    expectThat(Status.valueOf("Bad Request")).isEqualTo(Status.BadRequest)
    expectThat(Status.valueOf("missing")).isNull()
  }

  @Test
  fun `valueOf preserves non blank reason phrase`() {
    val custom = Status.valueOf(499, "Custom")

    expectThat(custom.code).isEqualTo(499)
    expectThat(custom.reasonPhrase).isEqualTo("Custom")
    expectThat(Status.valueOf(404, " ")).isEqualTo(Status.NotFound)
  }
}
