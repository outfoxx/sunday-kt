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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper
import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.Companion.AnyText
import io.outfoxx.sunday.MediaType.Companion.CBOR
import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.MediaType.Companion.JSONStructured
import io.outfoxx.sunday.MediaType.Companion.OctetStream
import io.outfoxx.sunday.MediaType.Companion.WWWFormUrlEncoded
import io.outfoxx.sunday.MediaType.Companion.X509CACert
import io.outfoxx.sunday.MediaType.Companion.X509UserCert

class MediaTypeEncoders(private val registered: Map<MediaType, MediaTypeEncoder>) {

  fun supports(mediaType: MediaType) =
    registered.keys.any { mediaType.compatible(it) }

  fun find(mediaType: MediaType) =
    registered.entries.firstOrNull { it.key.compatible(mediaType) }?.value

  class Builder(val registered: Map<MediaType, MediaTypeEncoder> = mapOf()) {

    fun registerDefaults() =
      this
        .registerData()
        .registerURL()
        .registerJSON()
        .registerCBOR()
        .registerText()
        .registerX509()

    fun registerURL(
      arrayEncoding: WWWFormURLEncoder.ArrayEncoding =
        WWWFormURLEncoder.ArrayEncoding.Unbracketed,
      boolEncoding: WWWFormURLEncoder.BoolEncoding =
        WWWFormURLEncoder.BoolEncoding.Literal,
      dateEncoding: WWWFormURLEncoder.DateEncoding =
        WWWFormURLEncoder.DateEncoding.FractionalSecondsSinceEpoch,
      mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()
    ): Builder =
      register(
        WWWFormURLEncoder(arrayEncoding, boolEncoding, dateEncoding, mapper),
        WWWFormUrlEncoded
      )

    fun registerData() =
      register(BinaryEncoder.default, OctetStream)

    fun registerJSON() =
      register(JSONEncoder.default, JSON, JSONStructured)

    fun registerJSON(mapper: JsonMapper) =
      register(JSONEncoder(mapper), JSON, JSONStructured)

    fun registerCBOR() =
      register(CBOREncoder.default, CBOR)

    fun registerCBOR(mapper: CBORMapper) =
      register(CBOREncoder(mapper), CBOR)

    fun registerText() =
      register(TextEncoder.default, AnyText)

    fun registerX509() =
      register(BinaryEncoder.default, X509CACert, X509UserCert)

    fun register(decoder: MediaTypeEncoder, vararg types: MediaType) =
      Builder(registered.plus(types.map { it to decoder }))

    fun build() = MediaTypeEncoders(registered)
  }

  companion object {

    val default = Builder().registerDefaults().build()
  }
}
