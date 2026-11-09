package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.TransportConfig
import io.outfoxx.sunday.spi.TransportProvider

class JdkTransportProvider : TransportProvider {

  override val id: String = "jdk"

  override fun create(config: TransportConfig): JdkTransport =
    JdkTransport(
      baseURI = config.baseURI,
      problemFactory = config.problemFactory,
      mediaTypeEncoders = config.mediaTypeEncoders,
      mediaTypeDecoders = config.mediaTypeDecoders,
      pathEncoders = config.pathEncoders,
      tokenProviders = config.tokenProviders,
      requestAdapter = config.requestAdapter,
      responseAdapter = config.responseAdapter,
    )

}
