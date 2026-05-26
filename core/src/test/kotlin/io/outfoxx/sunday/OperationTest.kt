package io.outfoxx.sunday

import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.problems.Problem
import io.outfoxx.sunday.problems.ProblemAdapter
import io.outfoxx.sunday.problems.ProblemFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import org.junit.jupiter.api.Test
import strikt.api.expectThrows
import java.net.URI
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

  private data class TestRequest(
    override val method: Method,
    override val uri: URI,
    override val headers: Headers,
  ) : Request {
    override suspend fun body(): Source? = null

    override suspend fun execute(): Response = error("unused")

    override fun start() = emptyFlow<Request.Event>()
  }

  private object CancelingTransport : Transport<Request>() {

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
    ): Request = TestRequest(method, URI.create("http://example.com"), emptyList())

    override suspend fun transportResponse(request: Request): Response = throw CancellationException("cancelled")

    override fun eventSource(requestSupplier: suspend (Headers) -> Request) = error("unused")

    override fun close(cancelOutstandingRequests: Boolean) = Unit

    override fun close() = Unit
  }

  @Test
  fun `nullable operations rethrow cancellation without nullify matching`() =
    runTest {
      val operation =
        CancelingTransport.nullableOperation<Unit, Unit, Request>(
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
}
