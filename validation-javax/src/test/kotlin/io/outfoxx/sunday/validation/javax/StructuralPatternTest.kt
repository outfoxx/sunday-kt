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
import io.outfoxx.sunday.validation.ModelGraph
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.validation.Validation

class StructuralPatternTest {
  @DynamicProperties.ObjectValue(
    fields = [
      DynamicProperties.Field(
        "items",
        "wire-items",
        true,
        [
          DynamicProperties.Shape(DynamicProperties.Kind.ARRAY),
          DynamicProperties.Shape(DynamicProperties.Kind.INTEGER),
        ],
      ),
      DynamicProperties.Field(
        "nullable",
        "wire-nullable",
        true,
        [
          DynamicProperties.Shape(DynamicProperties.Kind.STRING, nullable = true),
        ],
      ),
      DynamicProperties.Field(
        "optional",
        "wire-optional",
        false,
        [
          DynamicProperties.Shape(DynamicProperties.Kind.STRING),
        ],
      ),
    ],
  )
  private class Expected(
    @get:Schema(minItems = 2) val items: List<Int>,
    val nullable: String?,
    val optional: String?,
  ) {
    init {
      error("Structural validation must not construct a replacement model")
    }
  }

  private class Stored(
    val values: Map<String, Any?>,
  ) : ModelGraph {
    override fun validationFields() = values
  }

  private class Record(
    val values: Map<String, Any?>,
  ) : DynamicModel {
    override fun dynamicPropertyValues() = values

    override fun dynamicPropertySchema() =
      DynamicProperties(
        patterns =
          arrayOf(
            DynamicProperties.Pattern(
              ".*",
              Schema(),
              shape = arrayOf(DynamicProperties.Shape(DynamicProperties.Kind.OBJECT, model = Expected::class)),
            ),
          ),
      )
  }

  @Test
  fun `intersecting object schemas reuse native properties and retain wire presence without construction`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val previous = ModelValidation.validatorProvider
        ModelValidation.validatorProvider = { factory.validator }
        try {
          val items = mutableListOf(1, 2)
          val fields = linkedMapOf<String, Any?>("wire-items" to items, "wire-nullable" to null)
          val model = Record(mapOf("one" to Stored(fields), "two" to Stored(fields)))
          assertTrue(factory.validator.validate(model).isEmpty())
          items.clear()
          assertEquals(setOf("one.wire-items", "two.wire-items"), paths(factory.validator.validate(model)))
          items.addAll(listOf(1, 2))
          fields.remove("wire-nullable")
          assertEquals(setOf("one.wire-nullable", "two.wire-nullable"), paths(factory.validator.validate(model)))
          fields["wire-nullable"] = null
          fields["wire-optional"] = null
          assertEquals(setOf("one.wire-optional", "two.wire-optional"), paths(factory.validator.validate(model)))
          fields.remove("wire-optional")
          fields["wire-items"] = listOf(1, "invalid")
          assertEquals(setOf("one.wire-items[1]", "two.wire-items[1]"), paths(factory.validator.validate(model)))
        } finally {
          ModelValidation.validatorProvider = previous
        }
      }
  }

  @Test
  fun `decoded named values cannot be replaced with unvalidated maps or wire trees`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val model =
          Record(
            mapOf(
              "map" to mapOf("wire-items" to emptyList<Int>()),
              "tree" to ObjectMapper().readTree("{}"),
            ),
          )
        assertEquals(setOf("map", "tree"), paths(factory.validator.validate(model)))
      }
  }

  private fun paths(violations: Set<javax.validation.ConstraintViolation<Record>>) =
    violations.map { it.propertyPath.toString() }.toSet()
}
