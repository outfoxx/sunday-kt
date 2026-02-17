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

import io.outfoxx.sunday.MediaType
import kotlinx.io.Source
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Decoder for binary data that is related to a specific media type.
 *
 * @see MediaType
 */
interface MediaTypeDecoder {

  /**
   * Decodes binary data into a specific Java/Kotlin type.
   *
   * @param data Binary data source.
   * @param type Target Java/Kotlin type.
   * @return Instance of [T].
   */
  fun <T : Any> decode(
    data: Source,
    type: KType,
  ): T
}

/**
 * Decodes binary data into a specific Java/Kotlin type.
 *
 * The target Java/Kotlin type is deduced by the reified type parameter [T].
 *
 * @param data Binary data source.
 * @return Instance of [T].
 */
inline fun <reified T : Any> MediaTypeDecoder.decode(data: Source): T = decode(data, typeOf<T>())
