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

package io.outfoxx.sunday.validation.javax

import com.fasterxml.jackson.databind.ObjectMapper
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.validation.Validation

class DynamicModelTest {
  private open class Base : DynamicModel {
    val values = linkedMapOf<String, Any?>()
    var reads = 0

    override fun dynamicPropertyValues(): Map<String, Any?> {
      reads++
      return values
    }

    override fun dynamicPropertySchema() =
      DynamicProperties(
        patterns =
          arrayOf(
            DynamicProperties.Pattern(
              "^list-",
              Schema(requiredValue = true, minItems = 2),
              DynamicProperties.Kind.ARRAY,
            ),
          ),
      )
  }

  private class Child : Base() {
    override fun dynamicPropertySchema() =
      DynamicProperties(
        declared = arrayOf("id"),
        patterns =
          arrayOf(
            DynamicProperties.Pattern(
              "^list-",
              Schema(requiredValue = true, minItems = 2),
              DynamicProperties.Kind.ARRAY,
            ),
            DynamicProperties.Pattern("-unique$", Schema(uniqueItems = true), DynamicProperties.Kind.ARRAY),
            DynamicProperties.Pattern(
              "^number-",
              Schema(requiredValue = true, minimum = "2", multipleOf = "2"),
              DynamicProperties.Kind.INTEGER,
            ),
          ),
        closed = true,
      )
  }

  @Test
  fun `native model adapter applies concrete inherited rules once and detects changes`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val model = Child()
        val values = mutableListOf("one", "two")
        model.values.putAll(mapOf("id" to "declared", "list-unique" to values, "number-one" to 2))
        for (mode in listOf(ModelMode.Request::class.java, ModelMode.Response::class.java)) {
          model.reads = 0
          assertTrue(factory.validator.validate(model, mode).isEmpty())
          assertEquals(1, model.reads)
          values[1] = "one"
          val invalid = factory.validator.validate(model, mode)
          assertEquals(setOf("list-unique"), invalid.map { it.propertyPath.toString() }.toSet())
          assertEquals(setOf("sunday.validation.uniqueItems"), invalid.map { it.messageTemplate }.toSet())
          assertEquals(2, model.reads)
          values[1] = "two"
        }
        model.values["number-one"] = 3
        assertEquals(
          setOf("number-one"),
          factory.validator
            .validate(model)
            .map { it.propertyPath.toString() }
            .toSet(),
        )
        model.values["number-one"] = "2"
        assertEquals(
          setOf("sunday.validation.invalidValue"),
          factory.validator
            .validate(model)
            .map {
              it.messageTemplate
            }.toSet(),
        )
        model.values["number-one"] = 2
        model.values["other"] = null
        assertEquals(
          setOf("other"),
          factory.validator
            .validate(model)
            .map { it.propertyPath.toString() }
            .toSet(),
        )
      }
  }

  @Test
  fun `wire nodes and current values share exact numeric null and collection semantics`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val mapper = ObjectMapper()
        val model = Child()
        model.values["list-one"] = mapper.readTree("[1,2]")
        model.values["number-one"] = mapper.readTree("200000000000000000000000000000000000000")
        assertTrue(factory.validator.validate(model).isEmpty())
        model.values["number-one"] = mapper.readTree("200000000000000000000000000000000000001")
        assertEquals(
          setOf("sunday.validation.multipleOf"),
          factory.validator
            .validate(model)
            .map {
              it.messageTemplate
            }.toSet(),
        )
        model.values["number-one"] = mapper.readTree("null")
        assertEquals(
          setOf("sunday.validation.required"),
          factory.validator
            .validate(model)
            .map { it.messageTemplate }
            .toSet(),
        )
        model.values.remove("number-one")
        model.values["list-one"] = mapper.readTree("[1]")
        assertEquals(
          setOf("sunday.validation.minItems"),
          factory.validator
            .validate(model)
            .map { it.messageTemplate }
            .toSet(),
        )
      }
  }
}
