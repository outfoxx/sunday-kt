package io.outfoxx.sunday

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonMappingException
import io.outfoxx.sunday.http.HeaderNames
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.problems.Problem
import io.outfoxx.sunday.problems.ProblemFactory
import io.outfoxx.sunday.problems.ProblemReference
import io.outfoxx.sunday.problems.ProblemStatus
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

class TransportFailureResponseTest {

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

  private class TestTransport(
    private val response: Response,
    override val mediaTypeEncoders: MediaTypeEncoders = MediaTypeEncoders.default,
    override val mediaTypeDecoders: MediaTypeDecoders = MediaTypeDecoders.default,
    override val pathEncoders: Map<KClass<*>, PathEncoder> = PathEncoders.default,
    override val problemFactory: ProblemFactory = SundayHttpProblem.Factory,
  ) : Transport<Request>() {

    override val registeredProblemTypes: MutableMap<String, KClass<out Problem>> = mutableMapOf()

    override fun registerProblem(
      typeId: String,
      problemType: KClass<out Problem>,
    ) {
      registeredProblemTypes[typeId] = problemType
    }

    override suspend fun <B : Any> transportRequest(
      method: Method,
      pathTemplate: String,
      pathParameters: io.outfoxx.sunday.http.Parameters?,
      queryParameters: io.outfoxx.sunday.http.Parameters?,
      body: B?,
      contentTypes: List<MediaType>?,
      acceptTypes: List<MediaType>?,
      headers: io.outfoxx.sunday.http.Parameters?,
      purpose: RequestPurpose,
      requestValidation: PayloadValidator<B>?,
      parameterValidation: ParameterValidator?,
    ): Request = TestRequest(method, URI.create("http://example.com$pathTemplate"), emptyList())

    override suspend fun transportResponse(request: Request): Response = response

    override fun eventSource(requestSupplier: suspend (Headers) -> Request) = error("unused")

    override fun close(cancelOutstandingRequests: Boolean) = Unit

    override fun close() = Unit
  }

  @Test
  fun `standard members normalize on registered and generic transport paths`() =
    runTest {
      for (registered in listOf(false, true)) {
        for (member in listOf("type", "title", "status", "detail", "instance")) {
          val wrong =
            if (member == "status") {
              listOf("null", "true", "\"401\"", "[]", "{}")
            } else {
              listOf("null", "true", "42", "[]", "{}")
            }
          for (value in wrong) {
            val body =
              Buffer().apply {
                writeString("""{"$member":$value,"trace":null,"nested":{"detail":42}}""", Charsets.UTF_8)
              }
            val response =
              TestResponse(404, "Not Found", listOf(HeaderNames.CONTENT_TYPE to MediaType.Problem.value), body)
            val transport = TestTransport(response)
            if (registered) transport.registerProblem("about:blank", SundayHttpProblem::class)
            val problem = expectThrows<SundayHttpProblem> { transport.result<String>(Method.Get, "/") }.subject
            expectThat(problem.type.toString()).isEqualTo("about:blank")
            expectThat(problem.status).isEqualTo(404)
            expectThat(problem.title).isEqualTo("Not Found")
            expectThat(problem.detail).isEqualTo(null)
            expectThat(problem.instance).isEqualTo(null)
            expectThat(problem.extensions).isEqualTo(
              mutableMapOf<String, Any?>(
                "trace" to null,
                "nested" to mapOf("detail" to 42),
              ),
            )
          }
        }
      }
    }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", visible = true)
  class BlankProblem(
    @ProblemReference type: URI = URI.create("about:blank"),
    @ProblemStatus status: Int? = null,
  ) : SundayHttpProblem(type = type, status = status)

  @Test
  fun `registered polymorphic blank problems receive effective type`() =
    runTest {
      for (type in listOf("", "\"type\":null,", "\"type\":42,")) {
        val body = Buffer().apply { writeString("{$type\"extensions\":null}", Charsets.UTF_8) }
        val transport =
          TestTransport(
            TestResponse(
              409,
              "Conflict",
              listOf(HeaderNames.CONTENT_TYPE to MediaType.Problem.value),
              body,
            ),
          )
        transport.registerProblem("about:blank", BlankProblem::class)
        val problem = expectThrows<BlankProblem> { transport.result<String>(Method.Get, "/") }.subject
        expectThat(problem.status).isEqualTo(409)
        expectThat(problem.extensions).isEqualTo(mutableMapOf<String, Any?>("extensions" to null))
      }
    }

  @Test
  fun `generic extensions retain their name and arbitrary value`() =
    runTest {
      for (value in listOf("null", "42", "\"trace\"", "[1]", "{\"trace\":\"abc\"}")) {
        val body = Buffer().apply { writeString("{\"extensions\":$value}", Charsets.UTF_8) }
        val transport =
          TestTransport(
            TestResponse(
              409,
              "Conflict",
              listOf(HeaderNames.CONTENT_TYPE to MediaType.Problem.value),
              body,
            ),
          )
        val problem = expectThrows<SundayHttpProblem> { transport.result<String>(Method.Get, "/") }.subject
        val mapper =
          com.fasterxml.jackson.module.kotlin
            .jacksonObjectMapper()
        expectThat(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(problem.extensions["extensions"]))
          .isEqualTo(mapper.readTree(value))
        expectThat(problem.extensions.keys).isEqualTo(mutableSetOf("extensions"))
      }
    }

  @Test
  fun `fractional status is rejected before untyped numeric conversion`() =
    runTest {
      for (registered in listOf(false, true)) {
        val body = Buffer().apply { writeString("{\"status\":401.00000000000001}", Charsets.UTF_8) }
        val transport =
          TestTransport(
            TestResponse(
              404,
              "Not Found",
              listOf(HeaderNames.CONTENT_TYPE to MediaType.Problem.value),
              body,
            ),
          )
        if (registered) transport.registerProblem("about:blank", SundayHttpProblem::class)
        expectThrows<JsonMappingException> { transport.result<String>(Method.Get, "/") }
      }
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

      val factory = TestTransport(response)

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

      val factory = TestTransport(response)

      val thrown =
        expectThrows<SundayHttpProblem> {
          factory.result<String>(Method.Get, "/test")
        }.subject

      val data = thrown.extensions["responseData"] as ByteArray
      expectThat(data.toList()).isEqualTo(listOf(1, 2, 3))
    }

  @Test
  fun `wrong typed problem status uses HTTP status and title`() =
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

      val factory = TestTransport(response)

      val thrown =
        expectThrows<SundayHttpProblem> {
          factory.result<String>(Method.Get, "/test")
        }.subject

      expectThat(thrown.status).isEqualTo(400)
      expectThat(thrown.title).isEqualTo("Bad Request")
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

      val factory = TestTransport(response)

      expectThat(factory.result<Unit>(Method.Get, "/test")).isEqualTo(Unit)

      expectThrows<SundayError> {
        factory.result<String>(Method.Get, "/test")
      }.and {
        get { reason }.isEqualTo(SundayError.Reason.UnexpectedEmptyResponse)
      }
    }
}
