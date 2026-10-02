/*
 * Copyright 2020 Outfox, Inc.
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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.outfoxx.sunday.json.patch.PatchOp
import io.outfoxx.sunday.json.patch.UpdateOp
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

/** Exercises Jackson without generated field annotations or constructor defaults. */
class PatchDecodingTest {

  @JsonInclude(NON_EMPTY)
  data class Update(
    val name: UpdateOp<String>,
    val description: PatchOp<String>,
  )

  data class Container(
    val updates: List<UpdateOp<String>>,
  )

  private val mapper = jacksonObjectMapper()

  @Test
  fun `missing values remain unchanged and explicit null is delete only for PatchOp`() {
    val empty = mapper.readValue<Update>("{}")
    expectThat(empty.name).isEqualTo(PatchOp.none())
    expectThat(empty.description).isEqualTo(PatchOp.none())
    expectThat(mapper.writeValueAsString(empty)).isEqualTo("{}")
    val patch = mapper.readValue<Update>("""{"name":"new","description":null}""")
    expectThat(patch.name).isEqualTo(PatchOp.set("new"))
    expectThat(patch.description).isEqualTo(PatchOp.delete())
    expectThat(mapper.writeValueAsString(patch)).isEqualTo("""{"name":"new","description":null}""")
    val error =
      assertThrows(JsonMappingException::class.java) {
        mapper.readValue<Update>("""{"name":null}""")
      }
    expectThat(error.path.single().fieldName).isEqualTo("name")
  }

  @Test
  fun `root operations preserve generic values and reject unsupported deletion`() {
    expectThat(mapper.readValue<UpdateOp<String>>("\"value\""))
      .isEqualTo(PatchOp.set("value"))
    expectThat(mapper.readValue<PatchOp<Map<String, Int>>>("""{"count":2}"""))
      .isEqualTo(PatchOp.set(mapOf("count" to 2)))
    expectThat(mapper.readValue<PatchOp<String>>("null")).isEqualTo(PatchOp.delete())
    assertThrows(JsonMappingException::class.java) {
      mapper.readValue<UpdateOp<String>>("null")
    }
  }

  @Test
  fun `collection positions use operation type instead of enclosing property type`() {
    expectThat(mapper.readValue<Container>("""{"updates":["new"]}""").updates)
      .isEqualTo(listOf(PatchOp.set("new")))
    expectThat(mapper.readValue<List<PatchOp<String>>>("""["new",null]"""))
      .isEqualTo(listOf(PatchOp.set("new"), PatchOp.delete()))
    assertThrows(JsonMappingException::class.java) {
      mapper.readValue<Container>("""{"updates":[null]}""")
    }
  }
}
