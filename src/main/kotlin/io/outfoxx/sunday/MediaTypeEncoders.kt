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
      arrayEncoding: WWWFormURLEncoder.ArrayEncoding = WWWFormURLEncoder.ArrayEncoding.Bracketed,
      boolEncoding: WWWFormURLEncoder.BoolEncoding = WWWFormURLEncoder.BoolEncoding.Numeric,
      dateEncoding: WWWFormURLEncoder.DateEncoding = WWWFormURLEncoder.DateEncoding.MillisecondsSince1970,
      mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()
    ): Builder =
      register(WWWFormURLEncoder(arrayEncoding, boolEncoding, dateEncoding, mapper), WWWFormUrlEncoded)

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
      register(JSONEncoder(mapper), MediaType.JSON, MediaType.JSONStructured)

    fun registerCBOR() =
      registerCBOR(
        CBORMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
          .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS) as CBORMapper
      )

    fun registerCBOR(mapper: CBORMapper) =
      register(CBOREncoder(mapper), MediaType.CBOR)

    fun register(decoder: MediaTypeEncoder, vararg types: MediaType) =
      Builder(registered.plus(types.map { it to decoder }))

    fun build() = MediaTypeEncoders(registered)
  }

  companion object {

    val default = Builder().registerDefaults().build()
  }
}
