package io.outfoxx.sunday

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper
import io.outfoxx.sunday.MediaType.Companion.WWWFormUrlEncoded

class MediaTypeEncoders(private val registered: Map<MediaType, MediaTypeEncoder>) {

  fun supports(mediaType: MediaType) =
    registered.keys.any { mediaType.compatible(it) }

  fun find(mediaType: MediaType) =
    registered.entries.firstOrNull { it.key.compatible(mediaType) }?.value

  class Builder(val registered: Map<MediaType, MediaTypeEncoder> = mapOf()) {

    fun registerDefaults() = registerURL().registerJSON().registerCBOR().registerData()

    fun registerURL(
      arrayEncoding: URLEncoder.ArrayEncoding = URLEncoder.ArrayEncoding.Bracketed,
      boolEncoding: URLEncoder.BoolEncoding = URLEncoder.BoolEncoding.Numeric,
      dateEncoding: URLEncoder.DateEncoding = URLEncoder.DateEncoding.MillisecondsSince1970,
      mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()
    ): Builder =
      register(URLEncoder(arrayEncoding, boolEncoding, dateEncoding, mapper), WWWFormUrlEncoded)

    fun registerData() =
      register(BinaryEncoder(), MediaType.OctetStream)

    fun registerJSON() =
      registerJSON(
        JsonMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
          .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS) as JsonMapper
      )

    fun registerJSON(mapper: JsonMapper) =
      register(ObjectMapperEncoder(mapper), MediaType.JSON, MediaType.JSONStructured)

    fun registerCBOR() =
      registerCBOR(
        CBORMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
          .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS) as CBORMapper
      )

    fun registerCBOR(mapper: CBORMapper) =
      register(ObjectMapperEncoder(mapper), MediaType.CBOR)

    fun register(decoder: MediaTypeEncoder, vararg types: MediaType) =
      Builder(registered.plus(types.map { it to decoder }))

    fun build() = MediaTypeEncoders(registered)

  }

  companion object {

    val default = Builder().registerDefaults().build()

  }

}
