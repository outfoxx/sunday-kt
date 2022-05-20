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

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper
import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.Companion.AnyText
import io.outfoxx.sunday.MediaType.Companion.CBOR
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.MediaType.Companion.JSON
import io.outfoxx.sunday.MediaType.Companion.JSONStructured
import io.outfoxx.sunday.MediaType.Companion.OctetStream
import io.outfoxx.sunday.MediaType.Companion.X509CACert
import io.outfoxx.sunday.MediaType.Companion.X509UserCert

class MediaTypeDecoders(private val registered: Map<MediaType, MediaTypeDecoder>) {

  fun supports(mediaType: MediaType) =
    registered.keys.any { mediaType.compatible(it) }

  fun find(mediaType: MediaType) =
    registered.entries.firstOrNull { it.key.compatible(mediaType) }?.value

  class Builder(private val registered: Map<MediaType, MediaTypeDecoder> = mapOf()) {

    fun registerDefaults() =
      this
        .registerData()
        .registerJSON()
        .registerCBOR()
        .registerEventStream()
        .registerText()
        .registerX509()

    fun registerData() =
      register(BinaryDecoder(), OctetStream)

    fun registerJSON() =
      register(JSONDecoder.default, JSON, JSONStructured)

    fun registerJSON(mapper: JsonMapper) =
      register(JSONDecoder(mapper), JSON, JSONStructured)

    fun registerCBOR() =
      register(CBORDecoder.default, CBOR)

    fun registerCBOR(mapper: CBORMapper) =
      register(CBORDecoder(mapper), CBOR)

    fun registerText() =
      register(TextDecoder.default, AnyText)

    fun registerEventStream() =
      register(BinaryDecoder.default, EventStream)

    fun registerX509() =
      register(BinaryDecoder.default, X509CACert, X509UserCert)

    fun register(decoder: MediaTypeDecoder, vararg types: MediaType) =
      Builder(registered.plus(types.map { it to decoder }))

    fun build() = MediaTypeDecoders(registered)
  }

  companion object {

    val default = Builder().registerDefaults().build()
  }
}
