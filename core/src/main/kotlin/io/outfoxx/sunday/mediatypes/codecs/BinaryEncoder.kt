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

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.bytestring.ByteString
import kotlinx.io.transferFrom
import kotlinx.io.write
import java.io.InputStream

/**
 * Encodes binary data into a binary data [Source].
 *
 * Encoding from [ByteArray], [ByteString], [InputStream], and [Source]
 * is supported.
 */
class BinaryEncoder : MediaTypeEncoder {

  companion object {

    /**
     * Default binary encoder.
     */
    val default = BinaryEncoder()

  }

  override fun <B> encode(value: B): Source =
    when (value) {
      is ByteArray -> {
        val buffer = Buffer()
        buffer.write(value)
        buffer
      }

      is ByteString -> {
        val buffer = Buffer()
        buffer.write(value)
        buffer
      }

      is InputStream -> {
        val buffer = Buffer()
        value.use { buffer.transferFrom(it) }
        buffer
      }

      is Source -> value

      else -> throw IllegalArgumentException("Unsupported value for binary encode")
    }
}
