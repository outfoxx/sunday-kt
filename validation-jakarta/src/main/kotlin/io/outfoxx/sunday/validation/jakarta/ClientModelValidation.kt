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

import jakarta.ws.rs.ConstrainedTo
import jakarta.ws.rs.RuntimeType
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientResponseContext
import jakarta.ws.rs.client.ClientResponseFilter
import jakarta.ws.rs.ext.InterceptorContext
import jakarta.ws.rs.ext.ParamConverter
import jakarta.ws.rs.ext.ParamConverterProvider
import jakarta.ws.rs.ext.Provider
import jakarta.ws.rs.ext.ReaderInterceptor
import jakarta.ws.rs.ext.ReaderInterceptorContext
import jakarta.ws.rs.ext.WriterInterceptor
import jakarta.ws.rs.ext.WriterInterceptorContext
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/** Validates each client entity at the codec boundary using the application's native validator. */
@Provider
@ConstrainedTo(RuntimeType.CLIENT)
class ClientModelValidation :
  WriterInterceptor,
  ReaderInterceptor,
  ClientResponseFilter,
  ParamConverterProvider {

  /** Validates generated scalar parameters at their native wire conversion boundary. */
  override fun <T : Any?> getConverter(
    rawType: Class<T>,
    genericType: Type,
    annotations: Array<out Annotation>,
  ): ParamConverter<T>? {
    if (annotations.filterIsInstance<CascadedValues>().none { it.mode == ModelMode.Request::class }) return null
    // Quarkus erases Kotlin covariant collection elements to Object while retaining the
    // enclosing generic type. Resolve that element without replacing collection encoding.
    val element =
      (genericType as? ParameterizedType)
        ?.takeIf {
          (it.rawType as? Class<*>)?.let(Iterable::class.java::isAssignableFrom) == true
        }?.actualTypeArguments
        ?.singleOrNull()
    val valueType =
      if (rawType == Any::class.java) {
        (if (element is WildcardType) element.upperBounds.singleOrNull() else element) as? Class<*> ?: return null
      } else {
        rawType
      }
    val factory =
      valueType.methods.singleOrNull {
        it.name == "fromValue" &&
          Modifier.isStatic(it.modifiers) &&
          it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
          rawType.isAssignableFrom(it.returnType)
      } ?: return null
    return object : ParamConverter<T> {
      override fun fromString(value: String): T = rawType.cast(factory.invoke(null, value))

      override fun toString(value: T): String {
        if (value != null) ModelValidation.request(value)
        return value.toString()
      }
    }
  }

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
