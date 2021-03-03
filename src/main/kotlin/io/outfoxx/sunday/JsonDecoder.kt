package io.outfoxx.sunday

import com.fasterxml.jackson.databind.json.JsonMapper
import kotlin.reflect.KClass

class JsonDecoder(jsonMapper: JsonMapper) : ObjectMapperDecoder(jsonMapper), TextMediaTypeDecoder {

  override fun <T : Any> decode(data: String, type: KClass<T>): T =
    objectMapper.readValue(data, type.java)
}
