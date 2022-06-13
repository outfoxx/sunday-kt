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

package io.outfoxx.sunday.json.patch

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.ContextualDeserializer

/***
 * Json Merge Patch operation: None, Set
 */
@JsonSerialize(using = PatchOp.Serializer::class)
@JsonDeserialize(using = PatchOp.Deserializer::class)
sealed interface UpdateOp<T : Any>

/***
 * Json Merge Patch operation: None, Set, Delete
 */
@JsonSerialize(using = PatchOp.Serializer::class)
@JsonDeserialize(using = PatchOp.Deserializer::class)
sealed class PatchOp<T : Any> {

  data class Set<T : Any>(val value: T) : PatchOp<T>(), UpdateOp<T> {

    override fun toString() = "set($value)"
  }

  class Delete<T : Any> private constructor() : PatchOp<T>() {

    companion object {

      val instance = Delete<Any>()
    }

    override fun toString() = "delete"
  }

  class None<T : Any> private constructor() : PatchOp<T>(), UpdateOp<T> {

    companion object {

      val instance = None<Any>()
    }

    override fun toString() = "none"
  }

  @Suppress("UNCHECKED_CAST")
  companion object {

    fun <T : Any> set(value: T) = Set(value)
    fun <T : Any> delete() = Delete.instance as Delete<T>
    fun <T : Any> none() = None.instance as None<T>
    fun <T : Any> setOrDelete(value: T?) =
      if (value != null) {
        set(value)
      } else {
        delete()
      }
  }

  class Serializer : JsonSerializer<PatchOp<*>>() {

    override fun isEmpty(provider: SerializerProvider?, value: PatchOp<*>?): Boolean =
      value is PatchOp.None

    override fun serialize(value: PatchOp<*>, gen: JsonGenerator, serializers: SerializerProvider) =
      when (value) {
        is Set<*> -> serializers.defaultSerializeValue(value.value, gen)
        is Delete<*> -> gen.writeNull()
        is PatchOp.None -> error("isEmpty should handle this state")
      }

  }

  object Deserializer : JsonDeserializer<PatchOp<Any>>(), ContextualDeserializer {

    class TypedDeserializer<T : Any>(private val type: JavaType) : JsonDeserializer<PatchOp<T>>() {

      override fun getNullValue(ctxt: DeserializationContext): PatchOp<T> = delete()

      override fun deserialize(p: JsonParser, ctxt: DeserializationContext): PatchOp<T> =
        Set(ctxt.readValue(p, type))

    }

    override fun createContextual(
      ctxt: DeserializationContext,
      property: BeanProperty
    ): JsonDeserializer<*> {
      return TypedDeserializer<Any>(property.type.containedType(0))
    }

    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): PatchOp<Any> =
      error("Should not be called")
  }

}


inline fun <T : Any> PatchOp<T>.use(block: (T?) -> Unit): Unit =
  when (this) {
    is PatchOp.Set -> block(value)
    is PatchOp.Delete -> block(null)
    is PatchOp.None -> Unit
  }

inline fun <T : Any> PatchOp<T>.getOrDefaultNullifyingDelete(current: () -> T?): T? =
  when (this) {
    is PatchOp.Set -> value
    is PatchOp.Delete -> null
    is PatchOp.None -> current()
  }

inline fun <T : Any> PatchOp<T>.getOrElse(block: (deleted: Boolean) -> Nothing): T =
  when (this) {
    is PatchOp.Set -> value
    is PatchOp.Delete -> block(true)
    is PatchOp.None -> block(false)
  }

inline fun <T : Any, R : Any> PatchOp<T>.map(transform: (T) -> R): PatchOp<R> =
  when (this) {
    is PatchOp.Set -> PatchOp.set(transform(value))
    is PatchOp.Delete -> PatchOp.delete()
    is PatchOp.None -> PatchOp.none()
  }


inline fun <T : Any> UpdateOp<T>.use(block: (T) -> Unit): Unit =
  when (this) {
    is PatchOp.Set -> block(value)
    is PatchOp.None -> Unit
  }

inline fun <T : Any> UpdateOp<T>.getOrDefault(current: () -> T): T =
  when (this) {
    is PatchOp.Set -> value
    is PatchOp.None -> current()
  }

inline fun <T : Any, R : Any> UpdateOp<T>.map(transform: (T) -> R): UpdateOp<R> =
  when (this) {
    is PatchOp.Set -> PatchOp.set(transform(value))
    is PatchOp.None -> PatchOp.none()
  }


interface Patch {

  fun <T : Any> set(value: T) = PatchOp.set(value)
  fun <T : Any> delete() = PatchOp.delete<T>()
  fun <T : Any> none() = PatchOp.none<T>()
  fun <T : Any> setOrDelete(value: T?) = PatchOp.setOrDelete(value)

}
