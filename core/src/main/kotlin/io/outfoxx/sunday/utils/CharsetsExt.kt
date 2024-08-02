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

package io.outfoxx.sunday.utils

import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.StandardParameterName.CharSet
import java.nio.charset.Charset

/**
 * Look up a [CharSet] based on a given [media type's][MediaType] `charset` parameter.
 *
 * @param mediaType [MediaType] to derive the [CharSet] from.
 * @param default If [mediaType] doesn't have a `charset` parameter, this value is returned.
 * @return Charset from the [mediaType] or the [default].
 */
fun Charsets.from(
  mediaType: MediaType,
  default: Charset = UTF_8,
): Charset {
  val encoding = mediaType.parameter(CharSet) ?: return default

  return Charset.forName(encoding)
}
