package io.outfoxx.sunday

import com.fasterxml.jackson.databind.ObjectMapper
import io.outfoxx.sunday.mediatypes.codecs.ObjectMapperDecoder
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull

class ObjectMapperDecoderTest {

  @Test
  fun `custom handler keeps problem type for unknown type ids`() {
    val handler = ObjectMapperDecoder.CustomDeserializationProblemHandler()
    val mapper = ObjectMapper()
    val baseType = mapper.typeFactory.constructType(Throwable::class.java)

    val resolved = handler.handleUnknownTypeId(null, baseType, "unknown", null, null)

    expectThat(resolved).isEqualTo(baseType)
  }

  @Test
  fun `custom handler defers for non problem types`() {
    val handler = ObjectMapperDecoder.CustomDeserializationProblemHandler()
    val mapper = ObjectMapper()
    val baseType = mapper.typeFactory.constructType(String::class.java)

    val resolved = handler.handleUnknownTypeId(null, baseType, "unknown", null, null)

    expectThat(resolved).isNull()
  }
}
