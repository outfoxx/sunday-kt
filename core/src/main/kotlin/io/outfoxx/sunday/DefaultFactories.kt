package io.outfoxx.sunday

import io.outfoxx.sunday.SundayError.Reason.MultipleProblemFactoryProviders
import io.outfoxx.sunday.SundayError.Reason.MultipleRequestFactoryProviders
import io.outfoxx.sunday.SundayError.Reason.NoProblemFactoryProvider
import io.outfoxx.sunday.SundayError.Reason.NoRequestFactoryProvider
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.problems.ProblemFactory
import io.outfoxx.sunday.spi.ProblemFactoryProvider
import io.outfoxx.sunday.spi.RequestFactoryProvider
import java.util.ServiceLoader
import kotlin.reflect.KClass

/**
 * Discover and create default request and problem factories via SPI providers on the classpath.
 *
 * Examples:
 * - DefaultFactories.requestFactory(URITemplate("https://api.example.com"))
 * - DefaultFactories.problemFactory(providerId = "quarkus")
 */
object DefaultFactories {

  fun availableRequestFactories(): List<RequestFactoryProvider> =
    ServiceLoader.load(RequestFactoryProvider::class.java).toList()

  fun availableProblemFactories(): List<ProblemFactoryProvider> =
    ServiceLoader.load(ProblemFactoryProvider::class.java).toList()

  fun requestFactory(
    baseURI: URITemplate,
    problemFactory: ProblemFactory = problemFactory(),
    providerId: String? = null,
    mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default,
    mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default,
    pathEncoders: Map<KClass<*>, PathEncoder> = PathEncoders.default,
  ): RequestFactory {
    val provider =
      selectRequestFactoryProvider(
        availableRequestFactories(),
        providerId,
      )

    val config =
      RequestFactoryConfig(
        baseURI = baseURI,
        problemFactory = problemFactory,
        mediaTypeEncoders = mediaTypeEncoders,
        mediaTypeDecoders = mediaTypeDecoders,
        pathEncoders = pathEncoders,
      )

    return provider.create(config)
  }

  fun problemFactory(providerId: String? = null): ProblemFactory =
    selectProblemFactoryProvider(availableProblemFactories(), providerId).create()

  internal fun selectRequestFactoryProvider(
    providers: List<RequestFactoryProvider>,
    providerId: String?,
  ): RequestFactoryProvider {
    if (providers.isEmpty()) {
      throw SundayError(
        NoRequestFactoryProvider,
        "Add sunday-okhttp or sunday-jdk to the classpath.",
      )
    }

    val provider =
      if (providerId != null) {
        providers.firstOrNull { it.id == providerId }
      } else if (providers.size == 1) {
        providers.first()
      } else {
        null
      }

    if (provider == null) {
      if (providerId == null) {
        val ids = providers.joinToString(", ") { it.id }
        throw SundayError(
          MultipleRequestFactoryProviders,
          "Multiple RequestFactory providers found: $ids. Specify providerId.",
        )
      }
      val ids = providers.joinToString(", ") { it.id }
      throw SundayError(
        NoRequestFactoryProvider,
        "Provider '$providerId' not found. Available providers: $ids.",
      )
    }

    return provider
  }

  internal fun selectProblemFactoryProvider(
    providers: List<ProblemFactoryProvider>,
    providerId: String?,
  ): ProblemFactoryProvider {
    if (providers.isEmpty()) {
      throw SundayError(
        NoProblemFactoryProvider,
        "Add sunday-problem, sunday-problem-quarkus, or sunday-problem-zalando to the classpath.",
      )
    }

    val provider =
      if (providerId != null) {
        providers.firstOrNull { it.id == providerId }
      } else if (providers.size == 1) {
        providers.first()
      } else {
        val maxPriority = providers.maxOf { it.priority }
        val candidates = providers.filter { it.priority == maxPriority }
        if (candidates.size == 1) candidates.first() else null
      }

    if (provider == null) {
      val ids = providers.joinToString(", ") { it.id }
      if (providerId == null) {
        throw SundayError(
          MultipleProblemFactoryProviders,
          "Multiple ProblemFactory providers found: $ids. Specify providerId.",
        )
      }
      throw SundayError(
        NoProblemFactoryProvider,
        "Provider '$providerId' not found. Available providers: $ids.",
      )
    }

    return provider
  }

}
