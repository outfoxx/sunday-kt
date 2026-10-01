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

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.outfoxx.sunday.validation.ModelGraph
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnionCommonPropertiesTest {
  private enum class State {
    @JsonProperty("active-state")
    ACTIVE,
  }

  @DynamicProperties.ObjectValue(
    fields = [
      DynamicProperties.Field("amount", "wire-amount", true, [DynamicProperties.Shape(DynamicProperties.Kind.NUMBER)]),
    ],
  )
  private class Nested(
    @get:Schema(maximum = "0.10000000000000000001") val amount: Number,
  ) {
    init {
      error("Validation must not construct the expected model")
    }
  }

  @Suppress("UNUSED_PARAMETER")
  private class Common(
    @DynamicProperties(
      declared = ["nested", "nullable", "state"],
      properties = [
        DynamicProperties.Property(
          "state",
          false,
          Schema(),
          [DynamicProperties.Shape(DynamicProperties.Kind.STRING, model = State::class)],
        ),
        DynamicProperties.Property(
          "nested",
          true,
          Schema(requiredValue = true),
          [DynamicProperties.Shape(DynamicProperties.Kind.OBJECT, model = Nested::class)],
        ),
        DynamicProperties.Property(
          "nullable",
          true,
          Schema(),
          [DynamicProperties.Shape(DynamicProperties.Kind.STRING, nullable = true)],
        ),
      ],
    ) values: Map<String, Any?>,
  ) {
    init {
      error("Validation must not construct a common model")
    }
  }

  private class Stored(
    val fields: MutableMap<String, Any?>,
  ) : ModelGraph {
    override fun validationFields() = fields
  }

  private class UseSite(
    @get:Schema(objectSchema = Common::class) val value: Stored,
  )

  @Test
  fun `wire checks preserve nested precision and presence without constructing models`() =
    withValidator {
      val mapper =
        ObjectMapper().enable(
          com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
        )

      fun values(json: String) = mapper.readTree(json).properties().associate { it.key to it.value }
      ModelValidation.response(
        values("""{"nested":{"wire-amount":0.10000000000000000001},"nullable":null,"state":"active-state"}"""),
        Common::class.java,
      )
      for (json in listOf(
        """{"nested":{"wire-amount":0.10000000000000000002},"nullable":null}""",
        """{"nested":{},"nullable":null}""",
        """{"nested":{"wire-amount":0.1}}""",
        """{"nested":{"wire-amount":0},"nullable":null,"state":"unknown"}""",
      )) {
        assertThrows(
          ConstraintViolationException::class.java,
        ) { ModelValidation.response(values(json), Common::class.java) }
      }
    }

  @Test
  fun `wire use-site constraints reuse the same native common rules`() =
    withValidator {
      val mapper = ObjectMapper()
      val validator = ModelValidation.validatorProvider()
      val valid = WireModelValue(mapper.readTree("""{"nested":{"wire-amount":0},"nullable":null}"""))
      assertTrue(validator.validateValue(UseSite::class.java, "value", valid).isEmpty())
      val invalid = WireModelValue(mapper.readTree("""{"nested":{"wire-amount":1},"nullable":null}"""))
      assertEquals(
        setOf(
          "value.nested.wire-amount",
        ),
        validator
          .validateValue(UseSite::class.java, "value", invalid)
          .map {
            it.propertyPath.toString()
          }.toSet(),
      )
    }

  @Test
  fun `use-site rules retain payload identity and are reapplied after mutation`() =
    withValidator {
      val nested = Stored(linkedMapOf("wire-amount" to "0.1".toBigDecimal()))
      val payload = Stored(linkedMapOf("nested" to nested, "nullable" to null))
      val use = UseSite(payload)
      assertTrue(ModelValidation.validatorProvider().validate(use).isEmpty())
      nested.fields["wire-amount"] = "0.10000000000000000002".toBigDecimal()
      assertTrue(ModelValidation.validatorProvider().validate(payload).isEmpty())
      val failures = ModelValidation.validatorProvider().validate(use)
      assertEquals(setOf("value.nested.wire-amount"), failures.map { it.propertyPath.toString() }.toSet())
      nested.fields["wire-amount"] = payload
      assertTrue(ModelValidation.validatorProvider().validate(use).isNotEmpty())
    }

  private fun withValidator(test: () -> Unit) {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val previous = ModelValidation.validatorProvider
        ModelValidation.validatorProvider = { factory.validator }
        try {
          test()
        } finally {
          ModelValidation.validatorProvider = previous
        }
      }
  }
}
