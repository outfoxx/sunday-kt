package io.outfoxx.sunday.problems

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class ProblemStandardMembersTest {
  private val mapper = jacksonObjectMapper()

  @Test
  fun `wrong categories equal absence without losing extensions`() {
    for (member in listOf("type", "title", "status", "detail", "instance")) {
      val wrong =
        if (member == "status") {
          listOf("null", "true", "false", "\"401\"", "[]", "{\"x\":[]}")
        } else {
          listOf("null", "true", "false", "42", "[]", "{\"x\":[]}")
        }
      val baseline = mapper.readValue<SundayHttpProblem>("""{"trace":null,"nested":{"detail":42}}""")
      for (value in wrong) {
        val actual = mapper.readValue<SundayHttpProblem>("""{"$member":$value,"trace":null,"nested":{"detail":42}}""")
        assertEquals(baseline, actual, "$member=$value")
        assertEquals(
          mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(baseline),
          mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(actual),
        )
      }
    }
    assertEquals("""{"type":"about:blank"}""", mapper.writeValueAsString(SundayHttpProblem()))
  }

  class Custom(
    @ProblemString detail: String? = "default",
    val required: String,
    val nullable: String? = null,
  ) : SundayHttpProblem(detail = detail)

  @Test
  fun `creator defaults and inherited inclusion are property scoped`() {
    for (value in listOf("", ",\"detail\":null", ",\"detail\":false", ",\"detail\":[{}]")) {
      val problem = mapper.readValue<Custom>("""{"required":"yes"$value}""")
      assertEquals("default", problem.detail)
      val encoded = mapper.readTree(mapper.writeValueAsBytes(problem))
      assertTrue(encoded.has("nullable"))
      assertFalse(encoded.has("stackTrace"))
      assertFalse(encoded.has("status"))
    }
    assertThrows(Exception::class.java) { mapper.readValue<Custom>("{}") }
  }

  @Test
  fun `valid empty strings and nullable extensions survive`() {
    val json = """{"title":"","detail":"","instance":"","trace":null,"message":"explicit"}"""
    val problem = mapper.readValue<SundayHttpProblem>(json)
    assertEquals("", problem.title)
    assertEquals("", problem.detail)
    assertEquals(URI.create(""), problem.instance)
    assertTrue(mapper.readTree(mapper.writeValueAsBytes(problem)).has("trace"))
    assertEquals("explicit", problem.extensions["message"])
    assertEquals("explicit", mapper.readTree(mapper.writeValueAsBytes(problem))["message"].textValue())
  }

  @Test
  fun `correct categories still undergo semantic validation`() {
    for (json in listOf("""{"status":401.5}""", """{"status":2147483648}""", """{"instance":"invalid uri"}""")) {
      assertThrows(Exception::class.java) { mapper.readValue<SundayHttpProblem>(json) }
    }
    assertEquals(401, mapper.readValue<SundayHttpProblem>("""{"status":401.0}""").status)
  }

  @Test
  fun `union normalization preserves inputs and exact extension numbers`() {
    val input =
      mapper.readTree(
        """{"type":42,"title":false,"status":"404","detail":[],"instance":{},"extra":{"type":null,"number":9007199254740993}}""",
      )
    val original = input.deepCopy<com.fasterxml.jackson.databind.JsonNode>()
    val normalized = ProblemDecoding.normalized(input)
    assertEquals("about:blank", normalized["type"].textValue())
    assertEquals(setOf("type", "extra"), normalized.properties().map { it.key }.toSet())
    assertEquals(original["extra"], normalized["extra"])
    assertEquals(original, input)
    assertEquals(mapper.readTree("[]"), ProblemDecoding.normalized(mapper.readTree("[]")))
  }

}
