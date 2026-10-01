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

import java.lang.reflect.Method
import javax.ws.rs.ConstrainedTo
import javax.ws.rs.RuntimeType
import javax.ws.rs.client.ClientRequestContext
import javax.ws.rs.client.ClientResponseContext
import javax.ws.rs.client.ClientResponseFilter
import javax.ws.rs.ext.InterceptorContext
import javax.ws.rs.ext.Provider
import javax.ws.rs.ext.ReaderInterceptor
import javax.ws.rs.ext.ReaderInterceptorContext
import javax.ws.rs.ext.WriterInterceptor
import javax.ws.rs.ext.WriterInterceptorContext

/** Validates each client entity at the codec boundary using the application's native validator. */
@Provider
@ConstrainedTo(RuntimeType.CLIENT)
class ClientModelValidation :
  WriterInterceptor,
  ReaderInterceptor,
  ClientResponseFilter {

  override fun filter(
    request: ClientRequestContext,
    response: ClientResponseContext,
  ) {
    request.setProperty(RESPONSE_VALIDATION, response.status in 200..299)
  }

  override fun aroundWriteTo(context: WriterInterceptorContext) {
    context.entity?.let { value ->
      val schema = context.schema(request = true)
      if (schema == null) ModelValidation.request(value) else ModelValidation.request(value, schema.value.java)
    }
    context.proceed()
  }

  override fun aroundReadFrom(context: ReaderInterceptorContext): Any? =
    context.proceed().also { value ->
      if (value != null && context.getProperty(RESPONSE_VALIDATION) != false) {
        val schema = context.schema(request = false)
        if (schema == null) ModelValidation.response(value) else ModelValidation.response(value, schema.value.java)
      }
    }

  private fun InterceptorContext.schema(request: Boolean): EntitySchema? {
    // MicroProfile clients do not consistently propagate parameter annotations to their codec interceptors.
    val method = getProperty("org.eclipse.microprofile.rest.client.invokedMethod") as? Method
    if (method != null) {
      return if (request) {
        method.parameterAnnotations
          .flatMap { it.toList() }
          .filterIsInstance<EntitySchema>()
          .singleOrNull()
      } else {
        method.getAnnotation(EntitySchema::class.java)
      }
    }
    return annotations.orEmpty().filterIsInstance<EntitySchema>().singleOrNull()
  }

  private companion object {
    const val RESPONSE_VALIDATION = "io.outfoxx.sunday.validation.response"
  }

}
