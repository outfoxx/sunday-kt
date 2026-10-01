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

import jakarta.validation.ConstraintViolationException
import jakarta.ws.rs.ConstrainedTo
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.RuntimeType
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import jakarta.ws.rs.ext.WriterInterceptor
import jakarta.ws.rs.ext.WriterInterceptorContext

/** Validates generated successful response entities once, immediately before the codec writes them. */
@Provider
@ConstrainedTo(RuntimeType.SERVER)
class ServerModelValidation :
  ContainerResponseFilter,
  WriterInterceptor {
  override fun filter(
    request: ContainerRequestContext,
    response: ContainerResponseContext,
  ) {
    request.setProperty(RESPONSE_VALIDATION, response.status in 200..299)
  }

  override fun aroundWriteTo(context: WriterInterceptorContext) {
    val schema =
      context.annotations
        .orEmpty()
        .filterIsInstance<EntitySchema>()
        .singleOrNull()
    val value = context.entity
    if (schema != null && value != null && context.getProperty(RESPONSE_VALIDATION) != false) {
      try {
        ModelValidation.response(value, schema.value.java)
      } catch (failure: ConstraintViolationException) {
        // Invalid application output is a server failure, never a malformed client request.
        throw InternalServerErrorException("Response entity violates its schema", failure)
      }
    }
    context.proceed()
  }

  private companion object {
    const val RESPONSE_VALIDATION = "io.outfoxx.sunday.validation.response"
  }
}
