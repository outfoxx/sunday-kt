package io.outfoxx.sunday.problems

import io.outfoxx.sunday.spi.ProblemFactoryProvider

class QuarkusProblemFactoryProvider : ProblemFactoryProvider {

  override val id: String = "quarkus"

  override val priority: Int = 100

  override fun create(): ProblemFactory = QuarkusProblemFactory

}
