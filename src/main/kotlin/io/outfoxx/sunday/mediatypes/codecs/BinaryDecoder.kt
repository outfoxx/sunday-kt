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

import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.source
import java.io.InputStream
import kotlin.reflect.KType

class BinaryDecoder : MediaTypeDecoder {

  override fun <T : Any> decode(data: ByteArray, type: KType): T =
    @Suppress("UNCHECKED_CAST")
    when (type.classifier) {
      ByteArray::class -> data as T
      ByteString::class -> data.toByteString(0, data.size) as T
      InputStream::class -> data.inputStream() as T
      Source::class -> data.inputStream().source() as T
      else -> error("Unsupported type for binary decode")
    }
}
