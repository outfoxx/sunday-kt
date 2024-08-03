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

import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler
import com.fasterxml.jackson.databind.jsontype.TypeIdResolver
import okio.Source
import okio.buffer
import org.zalando.problem.AbstractThrowableProblem
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType

/**
 * Common Jackson [ObjectMapper] decoder that supports decoding
 * from binary data and structured [map][Map] data.
 */
open class ObjectMapperDecoder(
  objectMapper: ObjectMapper,
) : MediaTypeDecoder,
  StructuredMediaTypeDecoder {

  class CustomDeserializationProblemHandler : DeserializationProblemHandler() {

    override fun handleUnknownTypeId(
      ctxt: DeserializationContext?,
      baseType: JavaType?,
      subTypeId: String?,
      idResolver: TypeIdResolver?,
      failureMsg: String?,
    ): JavaType? {
      // Ensure deserialization of Problem subclasses can be done explicitly without
      // registration
      if (baseType?.isTypeOrSubTypeOf(AbstractThrowableProblem::class.java) == true) {
        return baseType
      }
      return super.handleUnknownTypeId(ctxt, baseType, subTypeId, idResolver, failureMsg)
    }
  }

  val objectMapper: ObjectMapper =
    objectMapper
      .copy()
      .addHandler(CustomDeserializationProblemHandler())

  override fun <T : Any> decode(
    data: Source,
    type: KType,
  ): T =
    objectMapper.readValue(
      data.buffer().inputStream(),
      objectMapper.typeFactory.constructType(type.javaType),
    )

  override fun <T : Any> decode(
    data: Map<String, Any>,
    type: KType,
  ): T = objectMapper.convertValue(data, objectMapper.typeFactory.constructType(type.javaType))
}
