package io.outfoxx.sunday.problems

import io.outfoxx.sunday.spi.ProblemFactoryProvider

class SundayProblemFactoryProvider : ProblemFactoryProvider {

  override val id: String = "sunday"

  override val priority: Int = 0

  override fun create(): ProblemFactory = SundayHttpProblem.Factory

}
