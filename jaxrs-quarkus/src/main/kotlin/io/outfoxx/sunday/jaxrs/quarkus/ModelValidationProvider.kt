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

package io.outfoxx.sunday.jaxrs.quarkus

import io.outfoxx.sunday.validation.jakarta.ModelValidation
import io.quarkus.runtime.Startup
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import jakarta.validation.Validator

/** Binds constructor and client validation to Quarkus's configured Hibernate Validator instance. */
@Startup
@Singleton
class ModelValidationProvider(
  validator: Validator,
) {
  private val previous = ModelValidation.validatorProvider
  private val provider = { validator }

  init {
    ModelValidation.validatorProvider = provider
  }

  /** Releases this application's binding without replacing a later application override. */
  @PreDestroy
  fun close() {
    if (ModelValidation.validatorProvider === provider) ModelValidation.validatorProvider = previous
  }
}
