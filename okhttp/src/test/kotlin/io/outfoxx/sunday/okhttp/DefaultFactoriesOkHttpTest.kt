package io.outfoxx.sunday.okhttp

import io.outfoxx.sunday.DefaultFactories
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains

class DefaultFactoriesOkHttpTest {

  @Test
  fun `registers okHttp request factory provider`() {
    val ids = DefaultFactories.availableRequestFactories().map { it.id }
    expectThat(ids).contains("okhttp")
  }

}
