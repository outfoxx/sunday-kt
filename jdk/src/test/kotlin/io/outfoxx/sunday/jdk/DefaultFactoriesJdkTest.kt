package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.DefaultFactories
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains

class DefaultFactoriesJdkTest {

  @Test
  fun `registers jdk request factory provider`() {
    val ids = DefaultFactories.availableRequestFactories().map { it.id }
    expectThat(ids).contains("jdk")
  }

}
