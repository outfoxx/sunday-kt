package io.outfoxx.sunday

import io.outfoxx.sunday.http.HeaderNames
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.problems.Problem
import io.outfoxx.sunday.problems.ProblemFactory
import io.outfoxx.sunday.problems.SundayHttpProblem
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.writeString
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.isEqualTo
import java.net.URI
import kotlin.reflect.KClass

class RequestFactoryFailureResponseTest {

  private data class TestRequest(
    override val method: Method,
    override val uri: URI,
    override val headers: Headers,
  ) : Request {
    override suspend fun body(): Source? = null

    override suspend fun execute(): Response = error("unused")

    override fun start() = emptyFlow<Request.Event>()
  }

  private data class TestResponse(
    override val statusCode: Int,
    override val reasonPhrase: String?,
    override val headers: Headers,
    override val body: Source?,
  ) : Response {
    override val trailers: Headers? = null
    override val request: Request = TestRequest(Method.Get, URI.create("http://example.com"), emptyList())
  }

  private class TestRequestFactory(
    private val response: Response,
    override val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default,
    override val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default,
    override val pathEncoders: Map<KClass<*>, PathEncoder> = PathEncoders.default,
    override val problemFactory: ProblemFactory = SundayHttpProblem.Factory,
  ) : RequestFactory() {

    override val registeredProblemTypes: Map<String, KClass<out Problem>> = emptyMap()

    override fun registerProblem(
      typeId: String,
      problemType: KClass<out Problem>,
    ) = Unit

    override suspend fun <B : Any> request(
      method: Method,
      pathTemplate: String,
      pathParameters: io.outfoxx.sunday.http.Parameters?,
      queryParameters: io.outfoxx.sunday.http.Parameters?,
      body: B?,
      contentTypes: List<MediaType>?,
      acceptTypes: List<MediaType>?,
      headers: io.outfoxx.sunday.http.Parameters?,
      purpose: RequestPurpose,
    ): Request = TestRequest(method, URI.create("http://example.com$pathTemplate"), emptyList())

    override suspend fun response(request: Request): Response = response

    override fun eventSource(requestSupplier: suspend (Headers) -> Request) = error("unused")

    override fun close(cancelOutstandingRequests: Boolean) = Unit

    override fun close() = Unit
  }

  @Test
  fun `unknown text failure includes response text`() =
    runTest {
      val body = Buffer().apply { writeString("boom", Charsets.UTF_8) }
      val response =
        TestResponse(
          statusCode = 500,
          reasonPhrase = "Internal Server Error",
          headers =
            listOf(
              HeaderNames.CONTENT_TYPE to "text/plain",
              HeaderNames.CONTENT_LENGTH to "4",
            ),
          body = body,
        )

      val factory = TestRequestFactory(response)

      val thrown =
        expectThrows<SundayHttpProblem> {
          factory.result<String>(Method.Get, "/test")
        }.subject

      expectThat(thrown.extensions["responseText"]).isEqualTo("boom")
    }

  @Test
  fun `unknown binary failure includes response bytes`() =
    runTest {
      val body = Buffer().apply { write(byteArrayOf(1, 2, 3)) }
      val response =
        TestResponse(
          statusCode = 500,
          reasonPhrase = "Internal Server Error",
          headers = listOf(HeaderNames.CONTENT_TYPE to MediaType.OctetStream.value),
          body = body,
        )

      val factory = TestRequestFactory(response)

      val thrown =
        expectThrows<SundayHttpProblem> {
          factory.result<String>(Method.Get, "/test")
        }.subject

      val data = thrown.extensions["responseData"] as ByteArray
      expectThat(data.toList()).isEqualTo(listOf(1, 2, 3))
    }

  @Test
  fun `problem status map populates status and title`() =
    runTest {
      val body =
        Buffer().apply {
          writeString(
            """{"type":"about:blank","status":{"code":499,"reasonPhrase":"Custom"},"detail":"Oops"}""",
            Charsets.UTF_8,
          )
        }
      val response =
        TestResponse(
          statusCode = 400,
          reasonPhrase = "Bad Request",
          headers = listOf(HeaderNames.CONTENT_TYPE to MediaType.Problem.value),
          body = body,
        )

      val factory = TestRequestFactory(response)

      val thrown =
        expectThrows<SundayHttpProblem> {
          factory.result<String>(Method.Get, "/test")
        }.subject

      expectThat(thrown.status).isEqualTo(499)
      expectThat(thrown.title).isEqualTo("Custom")
      expectThat(thrown.detail).isEqualTo("Oops")
    }

  @Test
  fun `empty responses return unit or fail for non unit`() =
    runTest {
      val response =
        TestResponse(
          statusCode = 204,
          reasonPhrase = "No Content",
          headers = emptyList(),
          body = null,
        )

      val factory = TestRequestFactory(response)

      expectThat(factory.result<Unit>(Method.Get, "/test")).isEqualTo(Unit)

      expectThrows<SundayError> {
        factory.result<String>(Method.Get, "/test")
      }.and {
        get { reason }.isEqualTo(SundayError.Reason.UnexpectedEmptyResponse)
      }
    }
}
