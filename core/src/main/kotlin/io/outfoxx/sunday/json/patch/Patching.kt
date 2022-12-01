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

/**
 * JSON Merge Patch update operations: [none][PatchOp.None], [set][PatchOp.Set]
 */
@JsonSerialize(using = PatchOp.Serializer::class)
@JsonDeserialize(using = PatchOp.Deserializer::class)
sealed interface UpdateOp<T : Any>

/**
 * JSON Merge Patch operations: [none][PatchOp.None], [set][PatchOp.Set], [delete][PatchOp.Delete]
 */
@JsonSerialize(using = PatchOp.Serializer::class)
@JsonDeserialize(using = PatchOp.Deserializer::class)
sealed class PatchOp<T : Any> {

  /**
   * JSON Merge Patch `set` operation that sets/replaces the current value.
   */
  data class Set<T : Any>(val value: T) : PatchOp<T>(), UpdateOp<T> {

    override fun toString() = "set($value)"
  }

  /**
   * JSON Merge Patch `delete` operation that deletes the current value.
   */
  class Delete<T : Any> private constructor() : PatchOp<T>() {

    companion object {

      val instance = Delete<Any>()
    }

    override fun toString() = "delete"
  }

  /**
   * JSON Merge Patch `none` operation that leaves the current value untouched.
   */
  class None<T : Any> private constructor() : PatchOp<T>(), UpdateOp<T> {

    companion object {

      val instance = None<Any>()
    }

    override fun toString() = "none"
  }

  @Suppress("UNCHECKED_CAST")
  companion object {

    /**
     * Creates a JSON Merge Patch [set][PatchOp.Set] operation from the provided
     * value that sets/replaces the current value.
     *
     * @param value Value to set/replace the current value with.
     * @return [PatchOp.Set] operation instance.
     */
    fun <T : Any> set(value: T) = Set(value)

    /**
     * Creates a JSON Merge Patch [delete][PatchOp.Delete] operation that deletes the
     * current value.
     *
     * @return [PatchOp.Delete] operation instance.
     */
    fun <T : Any> delete() = Delete.instance as Delete<T>

    /**
     * Creates a JSON Merge Patch [none][PatchOp.None] operation that leaves the current
     * value untouched.
     *
     * @return [PatchOp.None] operation instance.
     */
    fun <T : Any> none() = None.instance as None<T>

    /**
     * Creates a JSON Merge Patch [set][PatchOp.Set] or [delete][PatchOp.Delete] operation
     * from the provided value. If the value provided is not `null` a [set][PatchOp.Set] operation
     * is created to set/replace the current value. Alternatively, when the provided value
     * is `null` a [delete][PatchOp.Delete] operation is created to delete the current value.
     *
     * @param value Value to set/replace the current value or delete the current value.
     * @return [PatchOp.Set] or [PatchOp.Delete] operation instance.
     */
    fun <T : Any> setOrDelete(value: T?) =
      if (value != null) {
        set(value)
      } else {
        delete()
      }
  }

  /**
   * Custom Serializer for [patch][PatchOp] operations.
   */
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

  /**
   * Custom Deserializer for [patch][PatchOp] operations.
   */
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

/**
 * Allows using a patch operation by passing an appropriate value to the given [block] function.
 *
 * * `set` - If the [patch][PatchOp] is a [set][PatchOp.Set] the [block] will be called
 * with the new value.
 * * `delete` - If the [patch][PatchOp] is a [delete][PatchOp.Delete] the [block] will be
 * called with `null`.
 * * `none` - If the [patch][PatchOp] is a [none][PatchOp.None] the [block] will `not` be called.
 *
 * @param block Usage function to be called (or not) based on the patch operation.
 */
inline fun <T : Any> PatchOp<T>.use(block: (T?) -> Unit): Unit =
  when (this) {
    is PatchOp.Set -> block(value)
    is PatchOp.Delete -> block(null)
    is PatchOp.None -> Unit
  }

/**
 * Retrieves a value depending on the patch operation where `delete` is mapped to `null` and
 * `none` is mapped to the value returned by given the [current] function.
 *
 * * `set` - If the [patch][PatchOp] is a [set][PatchOp.Set], the new value will be returned.
 * * `delete` - If the [patch][PatchOp] is a [delete][PatchOp.Delete], `null` will be returned.
 * * `none` - If the [patch][PatchOp] is [none][PatchOp.None], the value returned by [current]
 * will be returned.
 *
 * @param current Value to be returned when the patch operation is `none`.
 * @return Value depending on the patch operation.
 */
inline fun <T : Any> PatchOp<T>.getOrDefaultNullifyingDelete(current: () -> T?): T? =
  when (this) {
    is PatchOp.Set -> value
    is PatchOp.Delete -> null
    is PatchOp.None -> current()
  }

/**
 * Retrieves a value for `set` operations and calls the [block] function for `delete` and
 * `none` operations.
 *
 * * `set` - If the [patch][PatchOp] is a [set][PatchOp.Set], the new value will be returned.
 * * `delete` - If the [patch][PatchOp] is a [delete][PatchOp.Delete], [block] is called with
 * the `deleted` parameter set to `true`.
 * * `none` - If the [patch][PatchOp] is [none][PatchOp.None], [block] is called with the
 * `deleted` parameter set to `false`.
 *
 * The provided [block] is function that returns [Nothing] and therefore must throw an exception
 * or some other equivalent operation.
 *
 * @param block Function to be called for `delete` or `none` operations.
 * @return Value depending on the patch operation.
 */
inline fun <T : Any> PatchOp<T>.getOrElse(block: (deleted: Boolean) -> Nothing): T =
  when (this) {
    is PatchOp.Set -> value
    is PatchOp.Delete -> block(true)
    is PatchOp.None -> block(false)
  }

/**
 * Transforms the value for `set` operations by calling the provided [transform] function
 * and creating a new [set][PatchOp.Set] operation; all other patch operations (`delete` and
 * `none`) are returned as is.
 *
 * @param transform Function to be called for `set` operations to transform the value.
 * @return Transformed `set` operation or a passed through `delete` or `none` operation.
 */
inline fun <T : Any, R : Any> PatchOp<T>.map(transform: (T) -> R): PatchOp<R> =
  when (this) {
    is PatchOp.Set -> PatchOp.set(transform(value))
    is PatchOp.Delete -> PatchOp.delete()
    is PatchOp.None -> PatchOp.none()
  }

/**
 * Allows using a patch update operation by passing an appropriate value to the given [block]
 * function.
 *
 * * `set` - If the [patch][PatchOp] is a [set][PatchOp.Set] the [block] will be called
 * with the new value.
 * * `none` - If the [patch][PatchOp] is a [none][PatchOp.None] the [block] will `not` be called.
 *
 * @param block Usage function to be called (or not) based on the patch operation.
 */
inline fun <T : Any> UpdateOp<T>.use(block: (T) -> Unit): Unit =
  when (this) {
    is PatchOp.Set -> block(value)
    is PatchOp.None -> Unit
  }

/**
 * Retrieves a value depending on the patch operation where `none` is mapped to the value
 * returned by given the [current] function.
 *
 * * `set` - If the [patch][PatchOp] is a [set][PatchOp.Set], the new value will be returned.
 * * `none` - If the [patch][PatchOp] is [none][PatchOp.None], the value returned by [current]
 * will be returned.
 *
 * @param current Value to be returned when the patch operation is `none`.
 * @return Value depending on the patch operation.
 */
inline fun <T : Any> UpdateOp<T>.getOrDefault(current: () -> T): T =
  when (this) {
    is PatchOp.Set -> value
    is PatchOp.None -> current()
  }

/**
 * Transforms the value for `set` operations by calling the provided [transform] function
 * and creating a new [set][PatchOp.Set] operation; `none` operations are returned as is.
 *
 * @param transform Function to be called for `set` operations to transform the value.
 * @return Transformed `set` operation or a passed through `delete` or `none` operation.
 */
inline fun <T : Any, R : Any> UpdateOp<T>.map(transform: (T) -> R): UpdateOp<R> =
  when (this) {
    is PatchOp.Set -> PatchOp.set(transform(value))
    is PatchOp.None -> PatchOp.none()
  }


/**
 * Patch interface that allows easy creation of [patch operations][PatchOp].
 *
 * This interface is used by the Sunday generator to generate patch types.
 */
interface Patch {

  /**
   * Creates a JSON Merge Patch [PatchOp.Set] operation from the provided
   * value that sets/replaces the current value.
   *
   * @param value Value to set/replace the current value with.
   * @return [PatchOp.Set] operation instance.
   */
  fun <T : Any> set(value: T) = PatchOp.set(value)

  /**
   * Creates a JSON Merge Patch [PatchOp.Delete] operation that deletes the current value.
   *
   * @return [PatchOp.Delete] operation instance.
   */
  fun <T : Any> delete() = PatchOp.delete<T>()

  /**
   * Creates a JSON Merge Patch [PatchOp.None] operation that leaves the current value untouched.
   *
   * @return [PatchOp.None] operation instance.
   */
  fun <T : Any> none() = PatchOp.none<T>()

  /**
   * Creates a JSON Merge Patch [PatchOp.Set] or [PatchOp.Delete] operation from the
   * provided value. If the value provided is not `null` a [PatchOp.Set] operation is
   * created to set/replace the current value. Alternatively, when the provided value
   * is `null` a [PatchOp.Delete] operation is created to delete the current value.
   *
   * @param value Value to set/replace the current value or delete the current value.
   * @return [PatchOp.Set] or [PatchOp.Delete] operation instance.
   */
  fun <T : Any> setOrDelete(value: T?) = PatchOp.setOrDelete(value)

}
