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

/**
 * Container for [MediaTypeEncoder]s that allows registering and
 * locating encoders for specific [media types][MediaType].
 */
class MediaTypeEncoders(private val registered: Map<MediaType, MediaTypeEncoder>) {

  /**
   * Check if the given [media type][MediaType] has an encoder registered.
   *
   * @return `true` if an encoder is available.
   */
  fun supports(mediaType: MediaType) =
    registered.keys.any { mediaType.compatible(it) }

  /**
   * Locates a compatible encoder for the given [media type][MediaType].
   *
   * @param mediaType Media type to locate encoder for.
   * @return Compatible [MediaTypeEncoder] or null if none found.
   */
  fun find(mediaType: MediaType) =
    registered.entries.firstOrNull { it.key.compatible(mediaType) }?.value

  /**
   * Builder for [MediaTypeEncoders].
   */
  class Builder(val registered: Map<MediaType, MediaTypeEncoder> = mapOf()) {

    /**
     * Registers all the default encoders.
     *
     * @return Fluent builder.
     */
    fun registerDefaults() =
      this
        .registerData()
        .registerURL()
        .registerJSON()
        .registerCBOR()
        .registerText()
        .registerX509()

    /**
     * Register a URL encoder.
     *
     * @param arrayEncoding Array encoding format to use.
     * @param boolEncoding Bool encoding format to use.
     * @param dateEncoding Date encoding format to use.
     * @param mapper Jackson object mapper to use.
     * @return Fluent builder.
     */
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

    /**
     * Registers the default binary encoder.
     *
     * @return Fluent builder.
     */
    fun registerData() =
      register(BinaryEncoder.default, OctetStream)

    /**
     * Registers the default JSON encoder.
     *
     * @return Fluent builder.
     */
    fun registerJSON() =
      register(JSONEncoder.default, JSON, JSONStructured)

    /**
     * Registers a custom JSON encoder.
     *
     * @param mapper Jackson mapper to use for encoding.
     * @return Fluent builder.
     */
    fun registerJSON(mapper: JsonMapper) =
      register(JSONEncoder(mapper), JSON, JSONStructured)

    /**
     * Registers the default CBOR encoder.
     *
     * @return Fluent builder.
     */
    fun registerCBOR() =
      register(CBOREncoder.default, CBOR)

    /**
     * Registers a custom CBOR encoder.
     *
     * @param mapper Jackson mapper to use for encoding.
     * @return Fluent builder.
     */
    fun registerCBOR(mapper: CBORMapper) =
      register(CBOREncoder(mapper), CBOR)

    /**
     * Registers the default UTF-8 text encoder.
     *
     * @return Fluent builder.
     */
    fun registerText() =
      register(TextEncoder.default, AnyText)

    /**
     * Registers a binary encoder for X509 types.
     *
     * @return Fluent builder.
     */
    fun registerX509() =
      register(BinaryEncoder.default, X509CACert, X509UserCert)

    /**
     * Registers an encoder with specific media types.
     *
     * @param encoder Encoder to register.
     * @param types Media types to associate with [encoder].
     * @return Fluent builder.
     */
    fun register(encoder: MediaTypeEncoder, vararg types: MediaType) =
      Builder(registered.plus(types.map { it to encoder }))

    /**
     * Builds the immutable [MediaTypeEncoders] instance.
     *
     * @return [MediaTypeEncoders] instance.
     */
    fun build() = MediaTypeEncoders(registered)
  }

  companion object {

    /**
     * Default encoder container with all the default encoders registered.
     */
    val default = Builder().registerDefaults().build()
  }
}
