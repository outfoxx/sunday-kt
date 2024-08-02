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

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper

/**
 * Encodes Java/Kotlin values into binary CBOR data using Jackson.
 *
 * @see [ObjectMapperEncoder]
 */
class CBOREncoder(
  cborMapper: CBORMapper,
) : ObjectMapperEncoder(cborMapper) {

  companion object {

    /**
     * Default CBOR encoder configured for Sunday compatibility.
     */
    val default =
      CBOREncoder(
        CBORMapper()
          .findAndRegisterModules()
          .enable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
          .enable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS) as CBORMapper,
      )

  }

}
