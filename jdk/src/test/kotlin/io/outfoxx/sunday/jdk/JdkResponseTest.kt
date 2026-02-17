package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.http.HeaderNames
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.writeString
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional

class JdkResponseTest {

  private class TestHttpResponse(
    private val status: Int,
    private val request: HttpRequest,
    private val headers: HttpHeaders,
    private val body: Source,
  ) : HttpResponse<Source> {

    override fun statusCode(): Int = status

    override fun request(): HttpRequest = request

    override fun previousResponse(): Optional<HttpResponse<Source>> = Optional.empty()

    override fun headers(): HttpHeaders = headers

    override fun body(): Source = body

    override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()

    override fun uri(): URI = request.uri()

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
  }

  @Test
  fun `wraps http response metadata`() {
    val request =
      HttpRequest
        .newBuilder(URI.create("http://example.com"))
        .GET()
        .build()

    val headers =
      HttpHeaders.of(
        mapOf(
          HeaderNames.CONTENT_TYPE to listOf("text/plain"),
          "X-Test" to listOf("a", "b"),
        ),
      ) { _, _ -> true }

    val body = Buffer().apply { writeString("ok", Charsets.UTF_8) }

    val response = TestHttpResponse(200, request, headers, body)
    val jdkResponse = JdkResponse(response, HttpClient.newHttpClient())

    expectThat(jdkResponse.statusCode).isEqualTo(200)
    expectThat(jdkResponse.reasonPhrase).isEqualTo("OK")
    expectThat(jdkResponse.headers.any { it.first.equals("X-Test", ignoreCase = true) && it.second == "a" })
      .isEqualTo(true)
    expectThat(jdkResponse.headers.any { it.first.equals("X-Test", ignoreCase = true) && it.second == "b" })
      .isEqualTo(true)
    expectThat(jdkResponse.body).isEqualTo(body)
  }
}
