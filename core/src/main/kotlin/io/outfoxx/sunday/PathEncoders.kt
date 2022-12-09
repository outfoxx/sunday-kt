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

package io.outfoxx.sunday

import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.reflect.KClass

typealias PathEncoder = (Any) -> String
typealias PathEncoderMap = Map<KClass<*>, PathEncoder>

object PathEncoders {

  val default: PathEncoderMap
    get() = mapOf(
      Enum::class to { encodeEnum(it as Enum<*>) }
    )

  fun <E : Enum<E>> encodeEnum(value: Enum<E>) =
    value.javaClass
      .getField(value.name)
      .getAnnotation(JsonProperty::class.java)
      ?.value
      ?: value.name

}

inline fun <reified T : Any> PathEncoderMap.add(
  type: KClass<T>,
  crossinline encoder: (T) -> String
): PathEncoderMap {
  return this + mapOf(type to { encoder.invoke(it as T) })
}

inline fun <reified T : Any> PathEncoderMap.add(
  crossinline encoder: (T) -> String
): PathEncoderMap {
  return add(T::class, encoder)
}
