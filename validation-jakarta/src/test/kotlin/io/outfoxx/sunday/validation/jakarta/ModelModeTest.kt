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

package io.outfoxx.sunday.validation.jakarta

import jakarta.validation.Valid
import jakarta.validation.Validation
import jakarta.validation.constraints.Min
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelModeTest {

  @KnownVariant(groups = [ModelMode.Request::class])
  class Unknown(
    val rawValue: String,
  )

  class Item(
    @field:Min(1) var count: Int,
    @field:Valid val status: Unknown,
  )

  @Test
  fun `directional groups preserve default constraints and fallback identity`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val validator = factory.validator
        val item = Item(1, Unknown("active"))
        assertTrue(validator.validate(item, ModelMode.Response::class.java).isEmpty())
        assertEquals(
          setOf("status"),
          validator
            .validate(item, ModelMode.Request::class.java)
            .map {
              it.propertyPath.toString()
            }.toSet(),
        )
        item.count = 0
        assertEquals(
          setOf("count", "status"),
          validator
            .validate(item, ModelMode.Request::class.java)
            .map {
              it.propertyPath.toString()
            }.toSet(),
        )
        assertEquals(
          setOf("count"),
          validator
            .validate(item, ModelMode.Response::class.java)
            .map {
              it.propertyPath.toString()
            }.toSet(),
        )
      }
  }
}
