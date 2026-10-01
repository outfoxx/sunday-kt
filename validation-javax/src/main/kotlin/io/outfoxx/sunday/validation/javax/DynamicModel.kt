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

import java.util.concurrent.ConcurrentHashMap
import javax.validation.Constraint
import javax.validation.ConstraintValidator
import javax.validation.ConstraintValidatorContext
import javax.validation.Payload
import kotlin.reflect.KClass

/** Supplies current property values and immutable native pattern metadata for this concrete model. */
@DynamicModel.Checked
interface DynamicModel {
  /** Native metadata for all effective inherited pattern and additional-property restrictions. */
  fun dynamicPropertySchema(): DynamicProperties

  /** Current participating fields in wire form; validity is never cached between calls. */
  fun dynamicPropertyValues(): Map<String, Any?>

  /** One model-boundary adapter inherited through the shared interface, including class hierarchies. */
  @Target(AnnotationTarget.CLASS)
  @Retention(AnnotationRetention.RUNTIME)
  @Constraint(validatedBy = [Validator::class])
  annotation class Checked(
    val message: String = "Invalid dynamic model",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
  )

  /** Caches only immutable schema metadata; each invocation reads current values. */
  class Validator : ConstraintValidator<Checked, DynamicModel> {
    private val schemas = ConcurrentHashMap<Class<*>, DynamicProperties.Validator>()

    override fun isValid(
      value: DynamicModel?,
      context: ConstraintValidatorContext,
    ): Boolean {
      if (value == null) return true
      val schema =
        schemas.computeIfAbsent(value.javaClass) {
          DynamicProperties.Validator(wireInput = false).apply { initialize(value.dynamicPropertySchema()) }
        }
      return schema.isValid(value.dynamicPropertyValues(), context)
    }
  }
}
