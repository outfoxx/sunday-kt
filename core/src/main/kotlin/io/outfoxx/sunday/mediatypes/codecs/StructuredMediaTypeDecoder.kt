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

package io.outfoxx.sunday.mediatypes.codecs

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Common interface for decoders that support decoding
 * from structured [maps][Map] in addition to binary data.
 */
interface StructuredMediaTypeDecoder : MediaTypeDecoder {

  /**
   * Decodes a structured [Map] into a specific Java/Kotlin type.
   *
   * @param data Structured map to decode.
   * @param type Target Java/Kotlin type.
   */
  fun <T : Any> decode(
    data: Map<String, Any>,
    type: KType,
  ): T
}

/**
 * Decodes a structured [Map] into a specific Java/Kotlin type.
 *
 * The target Java/Kotlin type is deduced by the reified type parameter [T].
 *
 * @param data Structured map to decode.
 */
inline fun <reified T : Any> StructuredMediaTypeDecoder.decode(data: Map<String, Any>): T = decode(data, typeOf<T>())
