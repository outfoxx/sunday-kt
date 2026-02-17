package io.outfoxx.sunday.spi

import io.outfoxx.sunday.problems.ProblemFactory

interface ProblemFactoryProvider {

  val id: String

  val priority: Int get() = 0

  fun create(): ProblemFactory

}
