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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModelGraphTest {
  @Test
  fun `raw JSON cycles retain object keys and array indexes`() {
    val factory = JsonNodeFactory.instance
    val root = factory.objectNode()
    val shared = factory.objectNode().put("value", 1)
    root.set<JsonNode>("first", shared)
    root.set<JsonNode>("second", shared)
    assertNull(ModelGraph.cycle(root))
    val children = factory.arrayNode().add(root)
    shared.set<JsonNode>("children", children)
    assertEquals(
      listOf(ModelGraph.Edge.Entry("first"), ModelGraph.Edge.Entry("children"), ModelGraph.Edge.Element(0)),
      ModelGraph.cycle(root),
    )
    shared.remove("children")
    assertNull(ModelGraph.cycle(root))
    root.set<JsonNode>("self", root)
    assertEquals(listOf(ModelGraph.Edge.Entry("self")), ModelGraph.cycle(root))
  }
}
