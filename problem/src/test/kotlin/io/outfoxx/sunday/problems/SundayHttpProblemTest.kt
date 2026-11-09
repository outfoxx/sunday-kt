package io.outfoxx.sunday.problems

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsKey
import strikt.assertions.getValue
import strikt.assertions.isEqualTo
import java.net.URI

class SundayHttpProblemTest {

  @Test
  fun `copy retains exception cause without serializing exception bookkeeping`() {
    val cause = IllegalStateException("internal failure")
    val problem = SundayHttpProblem(status = 400, cause = cause)
    val copy = problem.copy(detail = "updated")
    assertSame(cause, copy.cause)
    val mapper = jacksonObjectMapper()
    val json = mapper.valueToTree<JsonNode>(copy)
    assertFalse(json.has("cause"))
    val decoded = mapper.readValue("""{"status":400}""", SundayHttpProblem::class.java)
    assertNull(decoded.cause)
  }

  @Test
  fun `extensions are exposed and mutable`() {
    val problem =
      SundayHttpProblem(
        type = URI.create("urn:test:type"),
        title = "Title",
        status = 400,
        detail = "Detail",
        instance = URI.create("urn:test:instance"),
      )

    problem.extension("extra", "value")

    expectThat(problem.extensions())
      .containsKey("extra")
      .getValue("extra")
      .isEqualTo("value")
  }

  @Test
  fun `copy preserves fields and equality`() {
    val problem =
      SundayHttpProblem(
        type = URI.create("urn:test:type"),
        title = "Title",
        status = 400,
        detail = "Detail",
        instance = URI.create("urn:test:instance"),
        extensions = mutableMapOf("extra" to "value"),
      )

    val copy = problem.copy()

    expectThat(copy).isEqualTo(problem)
    expectThat(copy.hashCode()).isEqualTo(problem.hashCode())
    expectThat(copy.toString()).contains("SundayHttpProblem")
  }

  @Test
  fun `createMessage includes non null fields and extensions`() {
    val message =
      SundayHttpProblem.createMessage(
        type = URI.create("urn:test:type"),
        title = "Title",
        status = 400,
        detail = null,
        instance = URI.create("urn:test:instance"),
        extensions = mapOf("extra" to "value"),
      )

    expectThat(message).contains("type: urn:test:type")
    expectThat(message).contains("title: Title")
    expectThat(message).contains("status: 400")
    expectThat(message).contains("instance: urn:test:instance")
    expectThat(message).contains("extra: value")
  }
}
