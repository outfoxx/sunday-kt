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

import io.outfoxx.sunday.json.patch.PatchOp
import io.outfoxx.sunday.json.patch.UpdateOp
import jakarta.validation.Valid
import jakarta.validation.Validation
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PatchValidationTest {

  class Item(
    @field:Schema(minLength = 2) val name: String,
  )

  class Change(
    @param:Schema(requiredValue = true, minLength = 2)
    @field:Schema(requiredValue = true, minLength = 2)
    var text: PatchOp<String> = PatchOp.none(),
    @param:Schema(minLength = 2)
    @field:Schema(minLength = 2)
    var nullable: PatchOp<String> = PatchOp.none(),
    @param:Schema(minimum = "1")
    @field:Schema(minimum = "1")
    var count: UpdateOp<Int> = PatchOp.none(),
    @param:Valid @field:Valid var item: PatchOp<Item> = PatchOp.none(),
    @param:Valid @field:Valid var update: UpdateOp<Item> = PatchOp.none(),
  )

  @Test
  fun `native extractors preserve omission deletion constructor and mutable boundary semantics`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val validator = factory.validator
        val value = Change()
        assertTrue(validator.validate(value, ModelMode.Request::class.java).isEmpty())
        value.nullable = PatchOp.delete()
        value.item = PatchOp.delete()
        assertTrue(validator.validate(value).isEmpty())
        value.text = PatchOp.delete()
        value.count = PatchOp.set(0)
        value.item = PatchOp.set(Item("x"))
        value.update = PatchOp.set(Item("x"))
        assertEquals(
          setOf("text", "count", "item.name", "update.name"),
          validator.validate(value, ModelMode.Request::class.java).map { it.propertyPath.toString() }.toSet(),
        )
        val constructor =
          Change::class.java.getDeclaredConstructor(
            PatchOp::class.java,
            PatchOp::class.java,
            UpdateOp::class.java,
            PatchOp::class.java,
            UpdateOp::class.java,
          )
        assertEquals(
          4,
          validator
            .forExecutables()
            .validateConstructorParameters(
              constructor,
              arrayOf(value.text, value.nullable, value.count, value.item, value.update),
              ModelMode.Response::class.java,
            ).size,
        )
        value.text = PatchOp.set("ok")
        value.count = PatchOp.set(1)
        value.item = PatchOp.set(Item("ok"))
        value.update = PatchOp.set(Item("ok"))
        assertTrue(validator.validate(value).isEmpty())
      }
  }
}
