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

/**
 * Container for [MediaTypeDecoder]s that allows registering and
 * locating decoders for specific [media types][MediaType].
 */
class MediaTypeDecoders(private val registered: Map<MediaType, MediaTypeDecoder>) {

  /**
   * Check if the given [media type][MediaType] has a decoder registered.
   *
   * @return `true` if a decoder is available.
   */
  fun supports(mediaType: MediaType) =
    registered.keys.any { mediaType.compatible(it) }

  /**
   * Locates a compatible decoder for the given [media type][MediaType].
   *
   * @param mediaType Media type to locate decoder for.
   * @return Compatible [MediaTypeDecoder] or null if none found.
   */
  fun find(mediaType: MediaType) =
    registered.entries.firstOrNull { it.key.compatible(mediaType) }?.value

  /**
   * Builder for [MediaTypeDecoders].
   */
  class Builder(private val registered: Map<MediaType, MediaTypeDecoder> = mapOf()) {

    /**
     * Registers all the default decoders.
     *
     * @return Fluent builder.
     */
    fun registerDefaults() =
      this
        .registerData()
        .registerJSON()
        .registerCBOR()
        .registerEventStream()
        .registerText()
        .registerX509()

    /**
     * Registers the default binary decoder.
     *
     * @return Fluent builder.
     */
    fun registerData() =
      register(BinaryDecoder(), OctetStream)

    /**
     * Registers the default JSON decoder.
     *
     * @return Fluent builder.
     */
    fun registerJSON() =
      register(JSONDecoder.default, JSON, JSONStructured)

    /**
     * Registers a custom JSON decoder.
     *
     * @param mapper Jackson mapper to use for decoding.
     * @return Fluent builder.
     */
    fun registerJSON(mapper: JsonMapper) =
      register(JSONDecoder(mapper), JSON, JSONStructured)

    /**
     * Registers the default JSON decoder.
     *
     * @return Fluent builder.
     */
    fun registerCBOR() =
      register(CBORDecoder.default, CBOR)

    /**
     * Registers a custom CBOR encoder.
     *
     * @param mapper Jackson mapper to use for decoding.
     * @return Fluent builder.
     */
    fun registerCBOR(mapper: CBORMapper) =
      register(CBORDecoder(mapper), CBOR)

    /**
     * Registers the default UTF-8 text decoder.
     *
     * @return Fluent builder.
     */
    fun registerText() =
      register(TextDecoder.default, AnyText)

    /**
     * Registers a dummy binary decoder for Server-Sent Events streams.
     *
     * This mapping is only used a placeholder. SSE streams are always
     * decoded using an [io.outfoxx.sunday.EventParser].
     *
     * @return Fluent builder.
     */
    fun registerEventStream() =
      register(BinaryDecoder.default, EventStream)

    /**
     * Registers a binary decoder for X509 types.
     *
     * @return Fluent builder.
     */
    fun registerX509() =
      register(BinaryDecoder.default, X509CACert, X509UserCert)

    /**
     * Registers a decoder with specific media types.
     *
     * @param decoder Decoder to register.
     * @param types Media types to associate with [decoder].
     * @return Fluent builder.
     */
    fun register(decoder: MediaTypeDecoder, vararg types: MediaType) =
      Builder(registered.plus(types.map { it to decoder }))

    /**
     * Builds the immutable [MediaTypeDecoders] instance.
     *
     * @return [MediaTypeDecoders] instance.
     */
    fun build() = MediaTypeDecoders(registered)
  }

  companion object {

    /**
     * Default decoder container with all the default decoders registered.
     */
    val default = Builder().registerDefaults().build()
  }
}
