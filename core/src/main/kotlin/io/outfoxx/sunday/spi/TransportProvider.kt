package io.outfoxx.sunday.spi

import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.TransportConfig
import io.outfoxx.sunday.http.Request

interface TransportProvider {

  val id: String

  val priority: Int get() = 0

  fun create(config: TransportConfig): Transport<Request>

}
