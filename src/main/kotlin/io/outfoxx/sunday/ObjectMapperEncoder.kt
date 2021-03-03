package io.outfoxx.sunday

import com.fasterxml.jackson.databind.ObjectMapper

class ObjectMapperEncoder(private val objectMapper: ObjectMapper) : MediaTypeEncoder {

  override fun <B> encode(value: B): ByteArray =
    objectMapper.writeValueAsBytes(value)
}
