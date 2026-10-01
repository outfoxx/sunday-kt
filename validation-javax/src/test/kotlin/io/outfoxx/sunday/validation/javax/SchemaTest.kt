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

import io.outfoxx.sunday.validation.NominalValue
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.validation.Validation

class SchemaTest {

  class Probe(
    @param:Schema(minLength = 1, maxLength = 1, patterns = ["😀"])
    @get:Schema(minLength = 1, maxLength = 1, patterns = ["😀"])
    val text: String,
    @param:Schema(multipleOf = "0.1", minimum = "0", maximum = "1", numericElements = true)
    @get:Schema(multipleOf = "0.1", minimum = "0", maximum = "1", numericElements = true)
    val numbers: List<Double?>,
  )

  class PatternProbe(
    @get:Schema(patterns = ["abc"])
    val text: String,
  )

  class StringElements(
    @get:Schema(minLength = 2, maxLength = 3, patterns = ["^[A-Z]+$"], stringElements = true, minItems = 1)
    val codes: List<String?>,
  )

  @Test
  fun `inline element restrictions validate each string and retain collection bounds`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val validator = factory.validator
        assertTrue(validator.validate(StringElements(listOf("AB", "CD", null))).isEmpty())
        assertEquals(
          setOf("codes"),
          validator.validate(StringElements(emptyList())).map { it.propertyPath.toString() }.toSet(),
        )
        val values = mutableListOf("AB", "CD")
        val model = StringElements(values)
        assertTrue(validator.validate(model).isEmpty())
        values[1] = "bad"
        val failures = validator.validate(model, ModelMode.Request::class.java)
        assertEquals(setOf("codes[1]"), failures.map { it.propertyPath.toString() }.toSet())
        assertEquals(setOf("sunday.validation.pattern"), failures.map { it.message }.toSet())
      }
  }

  data class Count(
    override val value: Int,
  ) : NominalValue<Int>

  class NominalProbe(
    @get:Schema(restrictValues = true, allowedNumbers = ["2"], minimum = "1")
    val count: Count,
  )

  class Overloaded {
    constructor(
      @Schema(requiredValue = true, minLength = 2) text: String?,
    ) {
      text?.length
    }

    constructor(
      @Schema(minimum = "1") number: Int,
    ) {
      number.inc()
    }
  }

  @Test
  fun `constructor validation uses the declared signature even with null or equal arity overloads`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val previous = ModelValidation.validatorProvider
        ModelValidation.validatorProvider = { factory.validator }
        try {
          ModelValidation.constructor(Overloaded::class.java, arrayOf(String::class.java), "ok")
          ModelValidation.constructor(Overloaded::class.java, arrayOf(Int::class.java), 1)
          assertThrows(javax.validation.ConstraintViolationException::class.java) {
            ModelValidation.constructor(Overloaded::class.java, arrayOf(String::class.java), null)
          }
          assertThrows(javax.validation.ConstraintViolationException::class.java) {
            ModelValidation.constructor(Overloaded::class.java, arrayOf(Int::class.java), 0)
          }
        } finally {
          ModelValidation.validatorProvider = previous
        }
      }
  }

  @Test
  fun `constructor and property constraints share wire semantics`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val validator = factory.validator
        assertTrue(validator.validate(Probe("😀", listOf(0.3, null))).isEmpty())
        assertTrue(validator.validate(NominalProbe(Count(2))).isEmpty())
        assertEquals(
          setOf("count"),
          validator.validate(NominalProbe(Count(3))).map { it.propertyPath.toString() }.toSet(),
        )
        assertTrue(validator.validate(PatternProbe("xxabcxx")).isEmpty())
        val value = Probe("xx", listOf(0.3, 0.31, 2.0, Double.NaN))
        val properties = validator.validate(value, ModelMode.Request::class.java)
        assertEquals(
          setOf("text", "numbers[1]", "numbers[2]", "numbers[3]"),
          properties.map { it.propertyPath.toString() }.toSet(),
        )
        assertEquals(
          setOf(
            "sunday.validation.maxLength",
            "sunday.validation.pattern",
            "sunday.validation.multipleOf",
            "sunday.validation.maximum",
            "sunday.validation.nonFiniteNumber",
          ),
          properties.map { it.message }.toSet(),
        )
        val constructor = Probe::class.java.declaredConstructors.single()
        val arguments =
          validator.forExecutables().validateConstructorParameters(
            constructor,
            arrayOf("xx", value.numbers),
            ModelMode.Response::class.java,
          )
        assertEquals(properties.map { it.message }.sorted(), arguments.map { it.message }.sorted())
      }
  }
}
