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

package io.outfoxx.sunday.http

import io.outfoxx.sunday.SundayError
import io.outfoxx.sunday.SundayError.Reason.InvalidHeaderValue

object HeaderParameters {

  fun encode(parameters: Parameters?): List<Pair<String, String>> {
    parameters ?: return emptyList()

    return parameters.flatMap { encodeParameter(it.key, it.value) }
  }

  private fun encodeParameter(headerName: String, headerParameter: Any?) =
    when (headerParameter) {
      null -> emptyList()

      is Array<*> ->
        headerParameter.mapNotNull { element ->
          element?.let { headerName to validate(headerName, "$element") }
        }

      is Iterable<*> ->
        headerParameter.mapNotNull { element ->
          element?.let { headerName to validate(headerName, "$element") }
        }

      else -> listOf(headerName to validate(headerName, "$headerParameter"))
    }

  private val asciiEncoder = Charsets.US_ASCII.newEncoder()

  /**
   * Validate an encoded header value.
   *
   * Checks that each character is ASCII. Also disallowing NULL, CR, and LF.
   */
  private fun validate(headerName: String, headerValue: String): String {
    for (char in headerValue) {
      if (!asciiEncoder.canEncode(char) || isDisallowedChar(char)) {
        throw SundayError(InvalidHeaderValue, ": header=$headerName, value=$headerValue")
      }
    }
    return headerValue
  }

  private fun isDisallowedChar(char: Char): Boolean =
    char == 0.toChar() || char == '\r' || char == '\n'

}
