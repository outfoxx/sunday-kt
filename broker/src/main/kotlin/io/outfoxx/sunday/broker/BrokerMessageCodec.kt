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

package io.outfoxx.sunday.broker

import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Encodes and decodes broker payloads using Sunday media type codecs.
 */
class BrokerMessageCodec(
  private val encoders: MediaTypeEncoders = MediaTypeEncoders.default,
  private val decoders: MediaTypeDecoders = MediaTypeDecoders.default,
) {

  /**
   * Encode [value] as a broker message.
   */
  fun <T : Any> encode(
    value: T,
    contentType: String?,
    headers: Map<String, Any?> = emptyMap(),
    routingKey: String? = null,
  ): BrokerMessage {
    val mediaType = contentType?.let(MediaType::from) ?: MediaType.JSON
    val encoder =
      encoders.find(mediaType)
        ?: throw BrokerCodecException("No broker encoder registered for media type '$mediaType'")
    return BrokerMessage(
      body = encoder.encode(value).readByteArray(),
      contentType = contentType,
      headers = headers,
      routingKey = routingKey,
    )
  }

  /**
   * Decode [delivery] into [type].
   */
  fun <T : Any> decode(
    delivery: BrokerRawDelivery,
    type: KType,
  ): T {
    val contentType = delivery.message.contentType ?: MediaType.JSON.toString()
    val mediaType = MediaType.from(contentType)
    val decoder =
      decoders.find(mediaType)
        ?: throw BrokerCodecException("No broker decoder registered for media type '$mediaType'")
    val buffer = Buffer()
    buffer.write(delivery.message.body)
    return decoder.decode(buffer, type)
  }
}

/**
 * Decode [delivery] using the reified Kotlin type.
 */
inline fun <reified T : Any> BrokerMessageCodec.decode(delivery: BrokerRawDelivery): T = decode(delivery, typeOf<T>())

/**
 * Raised when broker payload codecs cannot encode or decode a message.
 */
class BrokerCodecException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
