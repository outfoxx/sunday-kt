package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.TransportConfig
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.spi.TransportProvider

class JdkTransportProvider : TransportProvider {

  override val id: String = "jdk"

  override fun create(config: TransportConfig): Transport<Request> =
    JdkTransport(
      baseURI = config.baseURI,
      problemFactory = config.problemFactory,
      mediaTypeEncoders = config.mediaTypeEncoders,
      mediaTypeDecoders = config.mediaTypeDecoders,
      pathEncoders = config.pathEncoders,
    )

}
