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

import okio.Source
import okio.buffer
import java.nio.charset.Charset
import kotlin.reflect.KType

/**
 * Decodes binary data into Java text containers.
 *
 * Decoding to [String], and [CharSequence] is supported.
 */
class TextDecoder(
  private val charset: Charset,
) : TextMediaTypeDecoder {

  companion object {

    /**
     * Default text decoder configured for UTF-8
     */
    val default = TextDecoder(Charsets.UTF_8)

  }

  override fun <T : Any> decode(
    data: Source,
    type: KType,
  ): T =
    @Suppress("UNCHECKED_CAST")
    when (type.classifier) {
      String::class, CharSequence::class -> data.buffer().readString(charset) as T
      else -> throw IllegalArgumentException("Unsupported type for text decode")
    }

  override fun <T : Any> decode(
    data: String,
    type: KType,
  ): T =
    @Suppress("UNCHECKED_CAST")
    when (type.classifier) {
      String::class, CharSequence::class -> data as T
      else -> throw IllegalArgumentException("Unsupported type for text decode")
    }
}
