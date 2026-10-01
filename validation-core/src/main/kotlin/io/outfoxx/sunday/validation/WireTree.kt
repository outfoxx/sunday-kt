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
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.util.JsonParserDelegate
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import java.time.temporal.TemporalAccessor

/** Retains decimal wire values while Jackson builds a validation view before model conversion. */
object WireTree {
  /**
   * Retains wire presence and exact numbers while using the codec's decoded temporal values.
   * Jackson may represent dates as numbers or arrays; decoding owns that conversion. Unknown keys
   * remain available to containing-schema constraints even if the selected model discards them.
   */
  fun decodedFields(
    tree: JsonNode,
    value: ModelGraph,
  ): Map<String, JsonNode> = decodedNode(tree, value).properties().associate { it.key to it.value }

  private fun decodedNode(
    node: JsonNode,
    value: Any?,
  ): JsonNode {
    if (node.isNull) return node
    val decoded = if (value is NominalValue<*>) value.value else value
    if (decoded is TemporalAccessor) return TextNode.valueOf(wireString(decoded))
    val fields =
      when (decoded) {
        is ModelGraph -> decoded.validationFields()
        is Map<*, *> -> decoded
        else -> null
      }
    if (node is ObjectNode && fields != null) {
      return node.objectNode().apply {
        node.properties().forEach { (name, child) -> set<JsonNode>(name, decodedNode(child, fields[name])) }
      }
    }
    val items =
      when (decoded) {
        is List<*> -> decoded
        is Array<*> -> decoded.asList()
        else -> null
      }
    if (node is ArrayNode && items != null) {
      return node.arrayNode().apply {
        node.forEachIndexed { index, child -> add(decodedNode(child, items.getOrNull(index))) }
      }
    }
    return node
  }

  /** Uses the caller's Jackson context and syntax checks without rounding decimal tokens to Double. */
  fun read(
    parser: JsonParser,
    context: DeserializationContext,
  ): JsonNode =
    context.readTree(
      object : JsonParserDelegate(parser) {
        override fun getNumberType(): JsonParser.NumberType =
          if (currentToken() == JsonToken.VALUE_NUMBER_FLOAT &&
            !isNaN
          ) {
            JsonParser.NumberType.BIG_DECIMAL
          } else {
            super.getNumberType()
          }

        override fun getNumberTypeFP(): JsonParser.NumberTypeFP =
          if (currentToken() == JsonToken.VALUE_NUMBER_FLOAT &&
            !isNaN
          ) {
            JsonParser.NumberTypeFP.BIG_DECIMAL
          } else {
            super.getNumberTypeFP()
          }
      },
    )
}
