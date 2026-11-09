package io.outfoxx.sunday

import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.ResponseEvent
import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.problems.Problem
import io.outfoxx.sunday.problems.ProblemAdapter
import io.outfoxx.sunday.problems.ProblemFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.Source
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.isEqualTo
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

class OperationTest {

  private object ThrowingProblemFactory : ProblemFactory {

    override fun typed(type: URI): ProblemFactory.Builder = error("unused")

    override fun from(status: Status): ProblemFactory.Builder = error("unused")

    override fun from(descriptor: ProblemFactory.Descriptor): Problem = error("unused")

    override fun adapter(): ProblemAdapter =
      object : ProblemAdapter {
        override fun getType(problem: Problem): URI = error("adapter should not inspect cancellation")

        override fun getTitle(problem: Problem): String? = error("adapter should not inspect cancellation")

        override fun getStatus(problem: Problem): Status? = error("adapter should not inspect cancellation")

        override fun getDetail(problem: Problem): String? = error("adapter should not inspect cancellation")

        override fun getInstance(problem: Problem): URI? = error("adapter should not inspect cancellation")

        override fun getExtensions(problem: Problem): Map<String, Any?> =
          error("adapter should not inspect cancellation")
      }
  }

  private class TestRequest(
    override val method: Method,
    override val uri: URI,
    override val headers: Headers,
  ) : Request<Unit> {
    override val transport = Unit

    override suspend fun body(): Source? = null
  }

  private object CancelingTransport : Transport<Unit, Unit>() {

    override val registeredProblemTypes: Map<String, KClass<out Problem>> = emptyMap()
    override val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default
    override val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default
    override val pathEncoders: Map<KClass<*>, PathEncoder> = PathEncoders.default
    override val problemFactory: ProblemFactory = ThrowingProblemFactory

    override fun registerProblem(
      typeId: String,
      problemType: KClass<out Problem>,
    ) = Unit

    override suspend fun <B : Any> transportRequest(
      method: Method,
      pathTemplate: String,
      pathParameters: Parameters?,
      queryParameters: Parameters?,
      body: B?,
      contentTypes: List<MediaType>?,
      acceptTypes: List<MediaType>?,
      headers: Parameters?,
      purpose: RequestPurpose,
      requestValidation: PayloadValidator<B>?,
      parameterValidation: ParameterValidator?,
    ): Request<Unit> = TestRequest(method, URI.create("http://example.com"), emptyList())

    override suspend fun transportResponse(request: Request<Unit>): Response<Unit> =
      throw CancellationException("cancelled")

    override fun eventSource(requestSupplier: suspend (Headers) -> Request<Unit>) = error("unused")

    override fun start(
      request: Request<Unit>,
      budget: io.outfoxx.sunday.security.AuthenticationRecoveryBudget,
    ): kotlinx.coroutines.flow.Flow<ResponseEvent<Unit>> = kotlinx.coroutines.flow.emptyFlow()

    override fun close(cancelOutstandingRequests: Boolean) = Unit

    override fun close() = Unit
  }

  private class CapturingPurposeTransport : Transport<Unit, Unit>() {

    val purposes = CopyOnWriteArrayList<RequestPurpose>()

    override val registeredProblemTypes: Map<String, KClass<out Problem>> = emptyMap()
    override val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default
    override val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default
    override val pathEncoders: Map<KClass<*>, PathEncoder> = PathEncoders.default
    override val problemFactory: ProblemFactory = ThrowingProblemFactory

    override fun registerProblem(
      typeId: String,
      problemType: KClass<out Problem>,
    ) = Unit

    override suspend fun <B : Any> transportRequest(
      method: Method,
      pathTemplate: String,
      pathParameters: Parameters?,
      queryParameters: Parameters?,
      body: B?,
      contentTypes: List<MediaType>?,
      acceptTypes: List<MediaType>?,
      headers: Parameters?,
      purpose: RequestPurpose,
      requestValidation: PayloadValidator<B>?,
      parameterValidation: ParameterValidator?,
    ): Request<Unit> {
      purposes += purpose
      return TestRequest(method, URI.create("http://example.com"), emptyList())
    }

    override suspend fun transportResponse(request: Request<Unit>): Response<Unit> = error("unused")

    override fun eventSource(requestSupplier: suspend (Headers) -> Request<Unit>): EventSource<Unit, Unit> =
      EventSource(requestSupplier, problemFactory, eventTimeout = null, requestStarter = ::start)

    override fun start(
      request: Request<Unit>,
      budget: io.outfoxx.sunday.security.AuthenticationRecoveryBudget,
    ): kotlinx.coroutines.flow.Flow<ResponseEvent<Unit>> = kotlinx.coroutines.flow.emptyFlow()

    override fun close(cancelOutstandingRequests: Boolean) = Unit

    override fun close() = Unit

    suspend fun awaitPurpose() {
      withTimeout(1_000) {
        while (purposes.isEmpty()) {
          yield()
        }
      }
    }
  }

  @Test
  fun `nullable operations rethrow cancellation without nullify matching`() =
    runTest {
      val operation =
        CancelingTransport.nullableOperation<Unit, Unit, Unit, Unit>(
          OperationSpec(
            method = Method.Get,
            pathTemplate = "/test",
          ),
          NullifySpec(statuses = listOf(404)),
        )

      expectThrows<CancellationException> {
        operation.executeOrNull()
      }
    }

  @Test
  fun `event sources build event requests`() =
    runTest {
      val transport = CapturingPurposeTransport()
      val eventSource = transport.eventSource(Method.Get, "/events")

      eventSource.connect()
      transport.awaitPurpose()
      eventSource.close()

      expectThat(transport.purposes.toList()).isEqualTo(listOf(Transport.RequestPurpose.Events))
    }

  @Test
  fun `event sources with bodies build event requests`() =
    runTest {
      val transport = CapturingPurposeTransport()
      val eventSource = transport.eventSource<Unit>(Method.Get, "/events", body = null)

      eventSource.connect()
      transport.awaitPurpose()
      eventSource.close()

      expectThat(transport.purposes.toList()).isEqualTo(listOf(Transport.RequestPurpose.Events))
    }

  @Test
  fun `event streams build event requests`() =
    runTest {
      val transport = CapturingPurposeTransport()
      val job =
        launch {
          transport
            .eventStream<Unit>(
              Method.Get,
              "/events",
              decoder = { _, _, _, _, _ -> Unit },
            ).collect()
        }

      transport.awaitPurpose()
      job.cancelAndJoin()

      expectThat(transport.purposes.toList()).isEqualTo(listOf(Transport.RequestPurpose.Events))
    }

  @Test
  fun `event streams with bodies build event requests`() =
    runTest {
      val transport = CapturingPurposeTransport()
      val job =
        launch {
          transport
            .eventStream<Unit, Unit>(
              Method.Get,
              "/events",
              body = null,
              decoder = { _, _, _, _, _ -> Unit },
            ).collect()
        }

      transport.awaitPurpose()
      job.cancelAndJoin()

      expectThat(transport.purposes.toList()).isEqualTo(listOf(Transport.RequestPurpose.Events))
    }
}
