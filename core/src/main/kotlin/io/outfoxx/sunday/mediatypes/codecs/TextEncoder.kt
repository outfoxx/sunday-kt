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

import okio.Buffer
import okio.Source
import java.nio.charset.Charset

/**
 * Encodes text into a binary data [Source].
 *
 * Encoding from [String], and [CharSequence] is supported.
 */
class TextEncoder(private val charset: Charset) : MediaTypeEncoder {

  companion object {

    /**
     * Default text encoder configured for UTF-8.
     */
    val default = TextEncoder(Charsets.UTF_8)

  }

  override fun <B> encode(value: B): Source =
    when (value) {
      is String -> Buffer().writeString(value, charset)
      is CharSequence -> Buffer().writeString(value.toString(), charset)
      else -> throw IllegalArgumentException("Unsupported value for text encode")
    }
}
