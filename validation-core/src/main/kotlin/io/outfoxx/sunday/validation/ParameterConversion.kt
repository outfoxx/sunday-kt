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

package io.outfoxx.sunday.validation

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/** Resolves generated scalar factories while preserving the framework's parameter encoding. */
object ParameterConversion {
  /** Finds a wire-value factory for a scalar or a framework-erased covariant collection element. */
  fun <T> stringFactory(
    rawType: Class<T>,
    genericType: Type,
  ): ((String) -> T)? {
    val valueType = if (rawType == Any::class.java) elementType(genericType) ?: return null else rawType
    val factory =
      valueType.methods.singleOrNull {
        it.name == "fromValue" &&
          Modifier.isStatic(it.modifiers) &&
          it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
          rawType.isAssignableFrom(it.returnType)
      } ?: return null
    return { value -> rawType.cast(factory.invoke(null, value)) }
  }

  private fun elementType(genericType: Type): Class<*>? {
    // Quarkus erases Kotlin covariant elements to Object but retains the enclosing generic type.
    // Resolve that element without taking over the framework's collection encoding.
    val container = genericType as? ParameterizedType ?: return null
    val rawContainer = container.rawType as? Class<*> ?: return null
    if (!Iterable::class.java.isAssignableFrom(rawContainer)) return null
    val element = container.actualTypeArguments.singleOrNull()
    return (if (element is WildcardType) element.upperBounds.singleOrNull() else element) as? Class<*>
  }
}
