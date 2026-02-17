package io.outfoxx.sunday.problems

import io.outfoxx.sunday.DefaultFactories
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains

class DefaultFactoriesProblemQuarkusTest {

  @Test
  fun `registers quarkus problem factory provider`() {
    val ids = DefaultFactories.availableProblemFactories().map { it.id }
    expectThat(ids).contains("quarkus")
  }

}
