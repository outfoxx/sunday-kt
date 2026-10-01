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

import io.outfoxx.sunday.validation.ModelGraph
import io.outfoxx.sunday.validation.SchemaRules
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/** Wire-schema constraints executed through the application's native Bean Validation provider. */
@Target(
  AnnotationTarget.FIELD,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.VALUE_PARAMETER,
  AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [Schema.Validator::class])
annotation class Schema(
  val objectSchema: KClass<*> = Any::class,
  val requiredValue: Boolean = false,
  val minLength: Int = -1,
  val maxLength: Int = -1,
  val patterns: Array<String> = [],
  val minimum: String = "",
  val exclusiveMinimum: Boolean = false,
  val maximum: String = "",
  val exclusiveMaximum: Boolean = false,
  val multipleOf: String = "",
  val minItems: Int = -1,
  val maxItems: Int = -1,
  val uniqueItems: Boolean = false,
  val numericElements: Boolean = false,
  val stringElements: Boolean = false,
  val restrictValues: Boolean = false,
  val allowedStrings: Array<String> = [],
  val allowedNumbers: Array<String> = [],
  val allowedBooleans: BooleanArray = [],
  val format: String = "",
  val message: String = "Invalid schema value",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
) {
  /** Keeps all predicates in the shared native constraint implementation. */
  class Validator : ConstraintValidator<Schema, Any> {
    private lateinit var rules: SchemaRules
    private var objectValidator: DynamicProperties.Validator? = null
    private var wireValidator: DynamicProperties.Validator? = null

    override fun initialize(annotation: Schema) {
      rules = annotation.rules()
      objectValidator =
        annotation.objectSchema.takeUnless { it == Any::class }?.let {
          DynamicProperties.Validator(wireInput = false).apply { initialize(DynamicProperties.from(it.java)) }
        }
      wireValidator =
        annotation.objectSchema.takeUnless { it == Any::class }?.let {
          DynamicProperties.Validator(wireInput = true).apply { initialize(DynamicProperties.from(it.java)) }
        }
    }

    override fun isValid(
      value: Any?,
      context: ConstraintValidatorContext,
    ): Boolean {
      var valid = true
      context.disableDefaultConstraintViolation()
      rules.failures(value) { reason, index ->
        valid = false
        val violation = context.buildConstraintViolationWithTemplate("sunday.validation.$reason")
        if (index == null) {
          violation.addConstraintViolation()
        } else {
          violation
            .addBeanNode()
            .inIterable()
            .atIndex(index)
            .addConstraintViolation()
        }
      }
      (if (value is WireModelValue) wireValidator else objectValidator)?.let { validator ->
        if (value is ModelGraph) {
          if (!validator.isValid(value.validationFields(), context)) valid = false
        } else if (value != null) {
          context.buildConstraintViolationWithTemplate("sunday.validation.invalidValue").addConstraintViolation()
          valid = false
        }
      }
      return valid
    }
  }
}

/** Reuses native schema metadata for property-pattern constraints without duplicating predicates. */
internal fun Schema.rules(): SchemaRules =
  SchemaRules(
    requiredValue,
    minLength,
    maxLength,
    patterns.toList(),
    minimum,
    exclusiveMinimum,
    maximum,
    exclusiveMaximum,
    multipleOf,
    minItems,
    maxItems,
    uniqueItems,
    numericElements,
    restrictValues,
    allowedStrings.toList(),
    allowedNumbers.toList(),
    allowedBooleans.toList(),
    format,
    stringElements,
  )
