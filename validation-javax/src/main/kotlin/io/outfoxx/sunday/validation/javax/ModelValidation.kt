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

package io.outfoxx.sunday.validation.javax

import javax.validation.ConstraintViolationException
import javax.validation.Validation
import javax.validation.Validator

/** Native validator adapters used by generated constructors and transport boundaries. */
object ModelValidation {

  private object DefaultValidator {
    val factory = Validation.buildDefaultValidatorFactory()
  }

  /** Supplies the application's native validator; frameworks can bind their managed instance. */
  @Volatile
  var validatorProvider: () -> Validator = { DefaultValidator.factory.validator }

  /** Validates a payload immediately before request encoding. */
  fun request(value: Any) {
    val violations = validatorProvider().validate(value, ModelMode.Request::class.java)
    if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
  }

  /** Validates root aliases and collections through their generated native schema view. */
  fun request(
    value: Any,
    schema: Class<*>,
  ) = schemaValue(value, schema, ModelMode.Request::class.java)

  /** Validates root aliases and collections in response mode without constructing application models. */
  fun response(
    value: Any,
    schema: Class<*>,
  ) = schemaValue(value, schema, ModelMode.Response::class.java)

  private fun schemaValue(
    value: Any,
    schema: Class<*>,
    mode: Class<*>,
  ) {
    if (schema == Any::class.java) {
      val violations = validatorProvider().validate(value, mode)
      if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
      return
    }
    val constructor = schema.declaredConstructors.single { it.parameterCount == 1 && !it.isSynthetic }
    val violations =
      validatorProvider().forExecutables().validateConstructorParameters(
        constructor,
        arrayOf(value),
        mode,
      )
    if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
  }

  /** Validates a decoded response without modifying it. */
  fun response(value: Any) {
    val violations = validatorProvider().validate(value, ModelMode.Response::class.java)
    if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
  }

  /** Checks initialized wire storage through the same native cycle constraint used by model validation. */
  fun graph(value: Any) {
    val violations = validatorProvider().validateValue(GraphView::class.java, "value", value)
    if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
  }

  private class GraphView(
    @get:Acyclic val value: Any,
  )

  /** Validates initialized constructor arguments without observing a partially initialized model. */
  fun constructor(
    type: Class<*>,
    parameterTypes: Array<Class<*>>,
    vararg values: Any?,
  ) {
    val constructor = type.getDeclaredConstructor(*parameterTypes)
    val violations =
      validatorProvider()
        .forExecutables()
        .validateConstructorParameters(constructor, values, ModelMode.Response::class.java)
    if (violations.isNotEmpty()) throw ConstraintViolationException(violations)
  }
}
