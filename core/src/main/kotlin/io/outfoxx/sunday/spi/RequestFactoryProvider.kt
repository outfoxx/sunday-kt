package io.outfoxx.sunday.spi

import io.outfoxx.sunday.RequestFactory
import io.outfoxx.sunday.RequestFactoryConfig

interface RequestFactoryProvider {

  val id: String

  val priority: Int get() = 0

  fun create(config: RequestFactoryConfig): RequestFactory

}
