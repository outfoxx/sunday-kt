package io.outfoxx.sunday.okhttp

import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.TransportConfig
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.spi.TransportProvider

class OkHttpTransportProvider : TransportProvider {

  override val id: String = "okhttp"

  override fun create(config: TransportConfig): Transport<Request> =
    OkHttpTransport(
      baseURI = config.baseURI,
      problemFactory = config.problemFactory,
      mediaTypeEncoders = config.mediaTypeEncoders,
      mediaTypeDecoders = config.mediaTypeDecoders,
      pathEncoders = config.pathEncoders,
    )

}
