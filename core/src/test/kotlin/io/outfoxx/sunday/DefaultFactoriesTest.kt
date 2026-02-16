package io.outfoxx.sunday

import io.outfoxx.sunday.spi.ProblemFactoryProvider
import io.outfoxx.sunday.spi.RequestFactoryProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class DefaultFactoriesTest {

  private class TestRequestProvider(
    override val id: String,
  ) : RequestFactoryProvider {
    override fun create(config: RequestFactoryConfig): RequestFactory = error("unused")
  }

  private class TestProblemProvider(
    override val id: String,
    override val priority: Int = 0,
  ) : ProblemFactoryProvider {
    override fun create() = error("unused")
  }

  @Test
  fun `request factory selection requires id when multiple available`() {
    val providers =
      listOf(
        TestRequestProvider("okhttp"),
        TestRequestProvider("jdk"),
      )

    val error =
      assertThrows<SundayError> {
        DefaultFactories.selectRequestFactoryProvider(providers, null)
      }

    expectThat(error.reason).isEqualTo(SundayError.Reason.MultipleRequestFactoryProviders)
  }

  @Test
  fun `request factory selection fails when none available`() {
    val error =
      assertThrows<SundayError> {
        DefaultFactories.selectRequestFactoryProvider(emptyList(), null)
      }

    expectThat(error.reason).isEqualTo(SundayError.Reason.NoRequestFactoryProvider)
  }

  @Test
  fun `problem factory selection prefers highest priority`() {
    val providers =
      listOf(
        TestProblemProvider("sunday", priority = 0),
        TestProblemProvider("quarkus", priority = 100),
      )

    val selected = DefaultFactories.selectProblemFactoryProvider(providers, null)

    expectThat(selected.id).isEqualTo("quarkus")
  }

  @Test
  fun `problem factory selection fails when none available`() {
    val error =
      assertThrows<SundayError> {
        DefaultFactories.selectProblemFactoryProvider(emptyList(), null)
      }

    expectThat(error.reason).isEqualTo(SundayError.Reason.NoProblemFactoryProvider)
  }

}
