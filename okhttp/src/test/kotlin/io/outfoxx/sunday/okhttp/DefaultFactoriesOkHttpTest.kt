package io.outfoxx.sunday.okhttp

import io.outfoxx.sunday.DefaultFactories
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains

class DefaultFactoriesOkHttpTest {

  @Test
  fun `registers okHttp transport provider`() {
    val ids = DefaultFactories.availableTransports().map { it.id }
    expectThat(ids).contains("okhttp")
  }

}
