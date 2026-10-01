/*
 * Copyright 2026 Outfox, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.outfoxx.sunday.validation

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class WireTreeTest {
  private class Stored(
    private val fields: Map<String, Any?>,
  ) : ModelGraph {
    override fun validationFields() = fields
  }

  private class Decoded(
    val value: JsonNode,
  )

  @Test
  fun `retains exact nested decimals without changing mapper configuration`() {
    val mapper =
      ObjectMapper().registerModule(
        SimpleModule().addDeserializer(
          Decoded::class.java,
          object : JsonDeserializer<Decoded>() {
            override fun deserialize(
              parser: JsonParser,
              context: DeserializationContext,
            ) = Decoded(WireTree.read(parser, context))
          },
        ),
      )
    val value =
      mapper
        .readValue(
          """{"items":[5.00000000000000000001,2],"enabled":true,"absent":null}""",
          Decoded::class.java,
        ).value
    assertEquals("5.00000000000000000001".toBigDecimal(), value["items"][0].decimalValue())
    assertEquals(2, value["items"][1].intValue())
    assertEquals(true, value["enabled"].booleanValue())
    assertEquals(true, value["absent"].isNull)
    assertFalse(mapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
  }

  @Test
  fun `decoded fields preserve presence precision and unknown keys while normalizing temporal encodings`() {
    val mapper = ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    val source =
      mapper.readTree(
        """{"at":1782388800,"nested":{"amount":0.10000000000000000001,"at":1782388800},"items":[[2026,6,25]],"null":null,"unknown":5}""",
      )
    val original = source.deepCopy<JsonNode>()
    val date = OffsetDateTime.parse("2026-06-25T12:00:00Z")
    val model =
      Stored(
        mapOf(
          "at" to date,
          "nested" to Stored(mapOf("amount" to 0.1, "at" to date)),
          "items" to listOf(LocalDate.of(2026, 6, 25)),
          "null" to date,
          "absent" to date,
        ),
      )
    val fields = WireTree.decodedFields(source, model)
    assertEquals(source.properties().map { it.key }.toSet(), fields.keys)
    assertEquals("2026-06-25T12:00:00Z", fields.getValue("at").textValue())
    assertEquals("2026-06-25T12:00:00Z", fields.getValue("nested")["at"].textValue())
    assertEquals("0.10000000000000000001".toBigDecimal(), fields.getValue("nested")["amount"].decimalValue())
    assertEquals("2026-06-25", fields.getValue("items")[0].textValue())
    assertEquals(true, fields.getValue("null").isNull)
    assertEquals(5, fields.getValue("unknown").intValue())
    assertEquals(original, source)
  }
}
