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

import com.fasterxml.jackson.core.Base64Variant
import com.fasterxml.jackson.core.Base64Variants
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.type.TypeFactory
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

class JSONDecoder(jsonMapper: JsonMapper) : ObjectMapperDecoder(jsonMapper), TextMediaTypeDecoder {

  companion object {

    val default =
      JSONDecoder(
        JsonMapper()
          .findAndRegisterModules()
          .setBase64Variant(
            Base64Variants.MIME_NO_LINEFEEDS
              .withReadPadding(Base64Variant.PaddingReadBehaviour.PADDING_ALLOWED)
              .withWritePadding(false)
          )
          .enable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
          .enable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS) as JsonMapper
      )

  }

  override fun <T : Any> decode(data: String, type: KType): T =
    objectMapper.readValue(data, TypeFactory.defaultInstance().constructType(type.javaType))
}
