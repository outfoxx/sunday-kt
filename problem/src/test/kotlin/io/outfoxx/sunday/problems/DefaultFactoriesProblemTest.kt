package io.outfoxx.sunday.problems

import io.outfoxx.sunday.DefaultFactories
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains

class DefaultFactoriesProblemTest {

  @Test
  fun `registers sunday problem factory provider`() {
    val ids = DefaultFactories.availableProblemFactories().map { it.id }
    expectThat(ids).contains("sunday")
  }

}
