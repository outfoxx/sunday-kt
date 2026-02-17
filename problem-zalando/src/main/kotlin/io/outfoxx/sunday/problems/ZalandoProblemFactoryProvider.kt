package io.outfoxx.sunday.problems

import io.outfoxx.sunday.spi.ProblemFactoryProvider

class ZalandoProblemFactoryProvider : ProblemFactoryProvider {

  override val id: String = "zalando"

  override val priority: Int = 100

  override fun create(): ProblemFactory = ZalandoProblemFactory

}
