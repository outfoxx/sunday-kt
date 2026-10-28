package io.outfoxx.sunday.problems

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/** RFC 9457 Int member: ignore wrong JSON categories and omit absent values. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@JacksonAnnotationsInside
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonSetter(nulls = Nulls.SKIP)
@JsonDeserialize(using = ProblemStatus.Deserializer::class)
annotation class ProblemStatus {

  /** Token-level normalization, before Kotlin creator defaults and validation. */
  class Deserializer : JsonDeserializer<Int>() {
    override fun deserialize(
      parser: JsonParser,
      context: DeserializationContext,
    ): Int? {
      if (parser.currentToken.isNumeric) {
        return try {
          parser.decimalValue.intValueExact()
        } catch (_: ArithmeticException) {
          context.reportInputMismatch(Int::class.java, "Problem status must be an exact 32-bit integer")
        }
      }
      parser.skipChildren()
      return null
    }
  }
}
