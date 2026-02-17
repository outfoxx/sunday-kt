package io.outfoxx.sunday

import io.outfoxx.sunday.http.HeaderNames
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.contentLength
import io.outfoxx.sunday.http.contentType
import io.outfoxx.sunday.http.isSuccessful
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.Source
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import java.net.URI

class ResponseExtensionsTest {

  private data class TestRequest(
    override val method: Method = Method.Get,
    override val uri: URI = URI.create("http://example.com"),
    override val headers: Headers = emptyList(),
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
    override val request: Request = TestRequest()
  }

  @Test
  fun `response extensions expose content headers`() {
    val response =
      TestResponse(
        statusCode = 204,
        reasonPhrase = "No Content",
        headers =
          listOf(
            HeaderNames.CONTENT_LENGTH to "0",
            HeaderNames.CONTENT_TYPE to "text/plain",
          ),
        body = null,
      )

    expectThat(response.isSuccessful).isTrue()
    expectThat(response.contentLength).isEqualTo(0L)
    expectThat(response.contentType).isEqualTo(MediaType.from("text/plain"))
  }
}
