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

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.Validation
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class CascadedValuesTest {
  @Target(AnnotationTarget.CLASS)
  @Retention(AnnotationRetention.RUNTIME)
  @Constraint(validatedBy = [Count.Validator::class])
  annotation class Count(
    val message: String = "counted",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
  ) {
    class Validator : ConstraintValidator<Count, Any> {
      override fun isValid(
        value: Any?,
        context: ConstraintValidatorContext,
      ): Boolean {
        calls++
        return true
      }
    }
  }

  @Count
  class Child(
    @get:Schema(minItems = 1) val values: MutableList<Int>,
  )

  @KnownVariant(groups = [ModelMode.Request::class])
  class Unknown(
    val rawValue: String,
  )

  class Holder(
    @get:CascadedValues(mode = ModelMode.Request::class, groups = [ModelMode.Request::class])
    @get:CascadedValues(mode = ModelMode.Response::class, groups = [ModelMode.Response::class])
    val extensions: Map<String, Any?>,
  )

  @Test
  fun `native predicates run once at each path through erased containers and recheck mutations`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val previous = ModelValidation.validatorProvider
        ModelValidation.validatorProvider = { factory.validator }
        try {
          val child = Child(mutableListOf(1))
          val holder = Holder(mapOf("records" to listOf(mapOf("first" to child, "second" to child))))
          for (mode in listOf(ModelMode.Request::class.java, ModelMode.Response::class.java)) {
            calls = 0
            assertTrue(factory.validator.validate(holder, mode).isEmpty())
            assertEquals(2, calls)
          }
          child.values.clear()
          calls = 0
          val errors = factory.validator.validate(holder, ModelMode.Request::class.java)
          assertEquals(
            setOf("extensions[records].[0].[first].values", "extensions[records].[0].[second].values"),
            errors.map { it.propertyPath.toString() }.toSet(),
          )
          assertEquals(2, calls)
          val unknown = Holder(mapOf("states" to mapOf("nested" to Unknown("active"))))
          assertTrue(factory.validator.validate(unknown, ModelMode.Response::class.java).isEmpty())
          assertEquals(
            setOf("extensions[states].[nested]"),
            factory.validator
              .validate(unknown, ModelMode.Request::class.java)
              .map { it.propertyPath.toString() }
              .toSet(),
          )
          val recursive = mutableMapOf<String, Any?>()
          recursive["self"] = recursive
          assertEquals(
            setOf("extensions[self]"),
            factory.validator
              .validate(Holder(recursive), ModelMode.Response::class.java)
              .map { it.propertyPath.toString() }
              .toSet(),
          )
        } finally {
          ModelValidation.validatorProvider = previous
        }
      }
  }

  companion object {
    private var calls = 0
  }
}
