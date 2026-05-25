package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.DefaultFactories
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains

class DefaultFactoriesJdkTest {

  @Test
  fun `registers jdk transport provider`() {
    val ids = DefaultFactories.availableTransports().map { it.id }
    expectThat(ids).contains("jdk")
  }

}
