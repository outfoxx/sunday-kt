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

package io.outfoxx.sunday.validation.jakarta

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.outfoxx.sunday.validation.ModelGraph
import jakarta.validation.Validation
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelGraphTest {
  class Node : SerializableModel {
    val children = mutableListOf<Node>()
    val metadata = linkedMapOf<String, Any?>()

    override fun validationFields(): Map<String, Any?> = mapOf("child-nodes" to children) + metadata
  }

  class CollectionView(
    @get:Acyclic val values: List<Any?>,
  )

  @Test
  fun `native groups reject cycles with wire paths and allow shared references`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val root = Node()
        val shared = Node()
        root.children += shared
        root.children += shared
        for (mode in listOf(ModelMode.Request::class.java, ModelMode.Response::class.java)) {
          assertTrue(factory.validator.validate(root, mode).isEmpty())
        }
        shared.metadata["parent"] = root
        for (mode in listOf(ModelMode.Request::class.java, ModelMode.Response::class.java)) {
          val violations = factory.validator.validate(root, mode)
          assertEquals(setOf("child-nodes[0].parent"), violations.map { it.propertyPath.toString() }.toSet())
          assertEquals(setOf("sunday.validation.cycle"), violations.map { it.message }.toSet())
        }
        shared.metadata.clear()
        assertTrue(factory.validator.validate(root).isEmpty())
        val values = mutableListOf<Any?>()
        values.add(values)
        val violations = factory.validator.validate(CollectionView(values))
        assertEquals(setOf("values[0]"), violations.map { it.propertyPath.toString() }.toSet())
        values.clear()
        assertNull(ModelGraph.cycle(values))
        val raw = JsonNodeFactory.instance.objectNode()
        val nested = JsonNodeFactory.instance.arrayNode().add(raw)
        raw.set<JsonNode>("children", nested)
        root.metadata["wire-json"] = raw
        val rawViolations = factory.validator.validate(root)
        assertEquals(setOf("wire-json[children].[0]"), rawViolations.map { it.propertyPath.toString() }.toSet())
        raw.remove("children")
        assertTrue(factory.validator.validate(root).isEmpty())
      }
  }
}
