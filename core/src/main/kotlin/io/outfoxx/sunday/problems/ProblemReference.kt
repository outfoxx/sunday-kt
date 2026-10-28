package io.outfoxx.sunday.problems

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import java.net.URI

/** RFC 9457 URI member: ignore wrong JSON categories and omit absent values. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@JacksonAnnotationsInside
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonSetter(nulls = Nulls.SKIP)
@JsonDeserialize(using = ProblemReference.Deserializer::class)
annotation class ProblemReference {

  /** Token-level normalization, before Kotlin creator defaults and validation. */
  class Deserializer : JsonDeserializer<URI>() {
    override fun deserialize(
      parser: JsonParser,
      context: DeserializationContext,
    ): URI? {
      if (parser.currentToken == JsonToken.VALUE_STRING) {
        return context.readValue(parser, URI::class.java)
      }
      parser.skipChildren()
      return null
    }
  }
}
