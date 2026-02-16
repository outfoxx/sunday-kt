package io.outfoxx.sunday.problems

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import io.outfoxx.sunday.MediaType.Companion.Problem
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.HeaderNames.CONTENT_TYPE
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.jdk.JdkRequestFactory
import io.outfoxx.sunday.mediatypes.codecs.JSONDecoder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.zalando.problem.AbstractThrowableProblem
import org.zalando.problem.Exceptional
import org.zalando.problem.ThrowableProblem
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.containsKey
import strikt.assertions.getValue
import strikt.assertions.isEqualTo
import java.net.URI
import kotlin.reflect.typeOf
import org.zalando.problem.Status as ZalandoStatus

class ZalandoProblemValidationTest {

  companion object {
    private val TYPE = URI.create("http://example.com/problems/generated")
    private const val TITLE = "Generated Problem"
    private const val DETAIL = "A generated problem"
    private val INSTANCE = URI.create("urn:test:instance")
    private const val EXTRA = "extra-value"
    private const val STATUS_CODE = 400
    private const val REASON_PHRASE = "Custom Reason"
    private val DEFAULT_TYPE = URI.create("about:blank")
    private val CUSTOM_STATUS = Status(599, REASON_PHRASE)

    private val problemPayload =
      mapOf<String, Any>(
        "type" to TYPE.toString(),
        "title" to TITLE,
        "status" to STATUS_CODE,
        "detail" to DETAIL,
        "instance" to INSTANCE.toString(),
        "extra" to EXTRA,
      )

    private val objectMapper =
      ObjectMapper()
        .findAndRegisterModules()
  }

  @Test
  fun `generated problems decode with jackson`() {
    val decoded: GeneratedProblem =
      JSONDecoder.default.decode(problemPayload, typeOf<GeneratedProblem>())

    expectThat(decoded.type).isEqualTo(TYPE)
    expectThat(decoded.title).isEqualTo(TITLE)
    expectThat(decoded.status?.statusCode).isEqualTo(STATUS_CODE)
    expectThat(decoded.detail).isEqualTo(DETAIL)
    expectThat(decoded.instance).isEqualTo(INSTANCE)
    expectThat(decoded.extra).isEqualTo(EXTRA)
  }

  @Test
  fun `registered problems decode as generated subclass`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(STATUS_CODE)
        .addHeader(CONTENT_TYPE, Problem)
        .setBody(objectMapper.writeValueAsString(problemPayload)),
    )
    server.start()
    server.use {
      createRequestFactory(server).use { requestFactory ->
        requestFactory.registerProblem(TYPE.toString(), GeneratedProblem::class)

        val thrown =
          expectThrows<GeneratedProblem> {
            requestFactory.result<String>(Method.Get, "/problem")
          }.subject

        expectThat(thrown.type).isEqualTo(TYPE)
        expectThat(thrown.title).isEqualTo(TITLE)
        expectThat(thrown.status?.statusCode).isEqualTo(STATUS_CODE)
        expectThat(thrown.detail).isEqualTo(DETAIL)
        expectThat(thrown.instance).isEqualTo(INSTANCE)
        expectThat(thrown.extra).isEqualTo(EXTRA)
      }
    }
  }

  @Test
  fun `unregistered problems decode as generic problem`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(STATUS_CODE)
        .addHeader(CONTENT_TYPE, Problem)
        .setBody(objectMapper.writeValueAsString(problemPayload)),
    )
    server.start()
    server.use {
      createRequestFactory(server).use { requestFactory ->
        val thrown =
          expectThrows<ThrowableProblem> {
            requestFactory.result<String>(Method.Get, "/problem")
          }.subject

        val adapter = ZalandoProblemFactory.adapter()

        expectThat(adapter.getType(thrown)).isEqualTo(TYPE)
        expectThat(adapter.getTitle(thrown)).isEqualTo(TITLE)
        expectThat(adapter.getStatus(thrown)).isEqualTo(Status.BadRequest)
        expectThat(adapter.getDetail(thrown)).isEqualTo(DETAIL)
        expectThat(adapter.getInstance(thrown)).isEqualTo(INSTANCE)
        expectThat(adapter.getExtensions(thrown))
          .containsKey("extra")
          .getValue("extra")
          .isEqualTo(EXTRA)
      }
    }
  }

  @Test
  fun `default title uses reason phrase for blank type`() {
    val problem = ZalandoProblemFactory.from(CUSTOM_STATUS).build()
    val adapter = ZalandoProblemFactory.adapter()

    expectThat(adapter.getType(problem)).isEqualTo(DEFAULT_TYPE)
    expectThat(adapter.getTitle(problem)).isEqualTo(REASON_PHRASE)
  }

  private fun createRequestFactory(server: MockWebServer) =
    JdkRequestFactory(
      URITemplate(server.url("/").toString()),
      problemFactory = ZalandoProblemFactory,
    )

  @JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    visible = true,
  )
  class GeneratedProblem(
    type: URI,
    title: String?,
    status: Int?,
    detail: String?,
    instance: URI?,
    extra: String,
  ) : AbstractThrowableProblem(
      type,
      title,
      status?.let(ZalandoStatus::valueOf),
      detail,
      instance,
      null,
      mapOf("extra" to extra),
    ) {
    val extra: String? by parameters

    override fun getCause(): Exceptional? = null
  }
}
