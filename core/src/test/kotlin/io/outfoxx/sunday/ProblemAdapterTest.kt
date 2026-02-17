package io.outfoxx.sunday

import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.problems.Problem
import io.outfoxx.sunday.problems.ProblemAdapter
import io.outfoxx.sunday.problems.ProblemFactory
import io.outfoxx.sunday.spi.ProblemFactoryProvider
import io.outfoxx.sunday.spi.RequestFactoryProvider
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import java.net.URI

class ProblemAdapterTest {

  private object Adapter : ProblemAdapter {
    override fun getType(problem: Problem): URI = URI.create("about:blank")

    override fun getTitle(problem: Problem): String? = null

    override fun getStatus(problem: Problem): Status? = null

    override fun getDetail(problem: Problem): String? = null

    override fun getInstance(problem: Problem): URI? = null

    override fun getExtensions(problem: Problem): Map<String, Any?> = mapOf("value" to 123)
  }

  @Test
  fun `getExtension reads from extension map`() {
    val problem = RuntimeException("boom")

    expectThat(Adapter.getExtension(problem, "value")).isEqualTo(123)
    expectThat(Adapter.getExtension(problem, "missing")).isNull()
  }

  @Test
  fun `default provider priority is zero`() {
    val requestProvider =
      object : RequestFactoryProvider {
        override val id: String = "request"

        override fun create(config: RequestFactoryConfig): RequestFactory = error("unused")
      }

    val problemProvider =
      object : ProblemFactoryProvider {
        override val id: String = "problem"

        override fun create(): ProblemFactory = error("unused")
      }

    expectThat(requestProvider.priority).isEqualTo(0)
    expectThat(problemProvider.priority).isEqualTo(0)
  }
}
