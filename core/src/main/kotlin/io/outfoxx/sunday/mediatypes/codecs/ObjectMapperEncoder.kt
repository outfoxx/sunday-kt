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

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.io.Buffer
import kotlinx.io.Source

/**
 * Common Jackson [ObjectMapper] encoder that supports encoding
 * Java/Kotlin values into binary data.
 */
open class ObjectMapperEncoder(
  private val objectMapper: ObjectMapper,
) : MediaTypeEncoder {

  override fun <B> encode(value: B): Source {
    val buffer = Buffer()
    buffer.write(objectMapper.writeValueAsBytes(value))
    return buffer
  }
}
