package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.RequestFactory
import io.outfoxx.sunday.RequestFactoryConfig
import io.outfoxx.sunday.spi.RequestFactoryProvider

class JdkRequestFactoryProvider : RequestFactoryProvider {

  override val id: String = "jdk"

  override fun create(config: RequestFactoryConfig): RequestFactory =
    JdkRequestFactory(
      baseURI = config.baseURI,
      problemFactory = config.problemFactory,
      mediaTypeEncoders = config.mediaTypeEncoders,
      mediaTypeDecoders = config.mediaTypeDecoders,
      pathEncoders = config.pathEncoders,
    )

}
