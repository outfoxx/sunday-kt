package io.outfoxx.sunday.problems

import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class SundayProblemFactoryProviderTest {

  @Test
  fun `provider creates sunday factory`() {
    val provider = SundayProblemFactoryProvider()

    expectThat(provider.id).isEqualTo("sunday")
    expectThat(provider.priority).isEqualTo(0)
    expectThat(provider.create()).isEqualTo(SundayHttpProblem.Factory)
  }
}
