package io.outfoxx.sunday

import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.problems.ProblemFactory
import kotlin.reflect.KClass

data class RequestFactoryConfig(
  val baseURI: URITemplate,
  val problemFactory: ProblemFactory,
  val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default,
  val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default,
  val pathEncoders: Map<KClass<*>, PathEncoder> = PathEncoders.default,
)
