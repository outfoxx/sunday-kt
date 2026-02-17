package io.outfoxx.sunday.okhttp

import io.outfoxx.sunday.RequestFactory
import io.outfoxx.sunday.RequestFactoryConfig
import io.outfoxx.sunday.spi.RequestFactoryProvider

class OkHttpRequestFactoryProvider : RequestFactoryProvider {

  override val id: String = "okhttp"

  override fun create(config: RequestFactoryConfig): RequestFactory =
    OkHttpRequestFactory(
      baseURI = config.baseURI,
      problemFactory = config.problemFactory,
      mediaTypeEncoders = config.mediaTypeEncoders,
      mediaTypeDecoders = config.mediaTypeDecoders,
      pathEncoders = config.pathEncoders,
    )

}
