package io.outfoxx.sunday

import com.fasterxml.jackson.databind.DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper
import io.outfoxx.sunday.MediaType.Companion.CBOR
import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.MediaType.Companion.JSONStructured
import io.outfoxx.sunday.MediaType.Companion.OctetStream

class MediaTypeDecoders(private val registered: Map<MediaType, MediaTypeDecoder>) {

  fun supports(mediaType: MediaType) =
    registered.keys.any { mediaType.compatible(it) }

  fun find(mediaType: MediaType) =
    registered.entries.firstOrNull { it.key.compatible(mediaType) }?.value

  class Builder(private val registered: Map<MediaType, MediaTypeDecoder> = mapOf()) {

    fun registerDefaults() = registerJSON().registerCBOR().registerData()

    fun registerData() =
      register(BinaryDecoder(), OctetStream)

    fun registerJSON() =
      registerJSON(
        JsonMapper()
          .findAndRegisterModules()
          .disable(WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
          .disable(READ_DATE_TIMESTAMPS_AS_NANOSECONDS) as JsonMapper
      )

    fun registerJSON(mapper: JsonMapper) =
      register(JsonDecoder(mapper), JSON, JSONStructured)

    fun registerCBOR() =
      registerCBOR(
        CBORMapper()
          .findAndRegisterModules()
          .disable(WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
          .disable(READ_DATE_TIMESTAMPS_AS_NANOSECONDS) as CBORMapper
      )

    fun registerCBOR(mapper: CBORMapper) =
      register(ObjectMapperDecoder(mapper), CBOR)

    fun register(decoder: MediaTypeDecoder, vararg types: MediaType) =
      Builder(registered.plus(types.map { it to decoder }))

    fun build() = MediaTypeDecoders(registered)

  }

  companion object {

    val default = Builder().registerDefaults().build()

  }

}
