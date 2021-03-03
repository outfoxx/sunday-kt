package io.outfoxx.sunday

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.reflect.KClass

open class ObjectMapperDecoder(val objectMapper: ObjectMapper) : MediaTypeDecoder {

  override fun <T : Any> decode(data: ByteArray, type: KClass<T>): T =
    objectMapper.readValue(data, type.java)
}
