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

import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.bytestring.ByteString
import kotlinx.io.readByteArray
import kotlinx.io.readByteString
import java.io.InputStream
import kotlin.reflect.KType

/**
 * Decodes binary data into binary data containers.
 *
 * Decoding to [ByteArray], [ByteString], [InputStream], and [Source]
 * is supported.
 */
class BinaryDecoder : MediaTypeDecoder {

  companion object {

    /**
     * Default binary decoder.
     */
    val default = BinaryDecoder()

  }

  override fun <T : Any> decode(
    data: Source,
    type: KType,
  ): T =
    @Suppress("UNCHECKED_CAST")
    when (type.classifier) {
      ByteArray::class -> data.readByteArray() as T
      ByteString::class -> data.readByteString() as T
      InputStream::class -> data.asInputStream() as T
      Source::class -> data as T
      else -> throw IllegalArgumentException("Unsupported type for binary decode")
    }
}
