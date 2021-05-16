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
import com.fasterxml.jackson.module.kotlin.convertValue
import io.outfoxx.sunday.http.Parameters
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter.ISO_INSTANT
import kotlin.text.Charsets.US_ASCII
import kotlin.text.Charsets.UTF_8
import kotlin.text.RegexOption.IGNORE_CASE

class WWWFormURLEncoder(
  private val arrayEncoding: ArrayEncoding = ArrayEncoding.Bracketed,
  private val boolEncoding: BoolEncoding = BoolEncoding.Numeric,
  private val dateEncoding: DateEncoding = DateEncoding.ISO8601,
  private val mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()
) : URLQueryParamsEncoder {

  companion object {

    val default = WWWFormURLEncoder()

    private val URI_ENCODE_COMPONENT_FIXES_REGEX = """\+|%21|%27|%28|%29|%7E""".toRegex(IGNORE_CASE)

    fun encodeURIComponent(value: Any): String {

      val result = URLEncoder.encode("$value", UTF_8)

      return URI_ENCODE_COMPONENT_FIXES_REGEX.replace(result) { matchResult ->
        when (matchResult.value) {
          "+" -> "%20"
          "%21" -> "!"
          "%27" -> "'"
          "%28" -> "("
          "%29" -> ")"
          "%7E" -> "~"
          else -> matchResult.value
        }
      }
    }

  }

  enum class ArrayEncoding(val encode: (String) -> String) {
    Bracketed({ "$it[]" }),
    Unbracketed({ it })
  }

  enum class BoolEncoding(val encode: (Boolean) -> String) {
    Numeric({ if (it) "1" else "0" }),
    Literal({ if (it) "true" else "false" })
  }

  enum class DateEncoding(val encode: (Instant) -> String) {
    SecondsSince1970({ "${it.epochSecond + (it.nano / 1_000_000_000.0)}" }),
    MillisecondsSince1970({ "${it.toEpochMilli()}" }),
    ISO8601({ ISO_INSTANT.format(it) })
  }

  override fun <T> encode(value: T): ByteArray {

    val parameters = mapper.convertValue<Map<String, Any>>(value as Any)

    return encodeQueryString(parameters).toByteArray(US_ASCII)
  }

  override fun encodeQueryString(parameters: Parameters): String =
    parameters.keys.sorted()
      .flatMap { key ->
        encodeQueryComponent(key, parameters[key]!!)
      }
      .joinToString("&") { "${it.first}=${it.second}" }

  private fun encodeQueryComponent(key: String, value: Any): List<Pair<String, String>> =
    when (value) {
      is Map<*, *> -> value.flatMap { (nestedKey, value) -> encodeQueryComponent("$key[$nestedKey]", value as Any) }
      is List<*> -> value.flatMap { element -> encodeQueryComponent(arrayEncoding.encode(key), element as Any) }
      is Instant -> listOf(encodeURIComponent(key) to encodeURIComponent(dateEncoding.encode(value)))
      is Boolean -> listOf(encodeURIComponent(key) to encodeURIComponent(boolEncoding.encode(value)))
      else -> listOf(encodeURIComponent(key) to encodeURIComponent("$value"))
    }

}
