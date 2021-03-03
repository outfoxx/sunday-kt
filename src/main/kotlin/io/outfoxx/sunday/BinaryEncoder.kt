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

import okio.Source
import okio.buffer
import java.io.InputStream
import kotlin.reflect.jvm.internal.impl.protobuf.ByteString

class BinaryEncoder : MediaTypeEncoder {

  override fun <B> encode(value: B): ByteArray =
    when (value) {
      is ByteArray -> value
      is ByteString -> value.toByteArray()
      is InputStream -> value.use { it.readAllBytes() }
      is Source -> value.use { it.buffer().readByteArray() }
      else -> error("Unsupported value for binary encode")
    }
}
