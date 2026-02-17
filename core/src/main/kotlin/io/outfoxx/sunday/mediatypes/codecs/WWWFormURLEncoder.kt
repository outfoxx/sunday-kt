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
import kotlinx.io.Buffer
import kotlinx.io.Source
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter.ISO_INSTANT
import java.util.concurrent.TimeUnit
import kotlin.text.Charsets.US_ASCII
import kotlin.text.Charsets.UTF_8
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Encodes Java/Kotlin values into application/www-url-form-encoded
 * strings and binary data [sources][Source].
 */
class WWWFormURLEncoder(
  private val arrayEncoding: ArrayEncoding,
  private val boolEncoding: BoolEncoding,
  private val dateEncoding: DateEncoding,
  private val mapper: ObjectMapper = ObjectMapper().findAndRegisterModules(),
) : URLQueryParamsEncoder {

  companion object {

    /**
     * Default URL encoder configured for Sunday compatibility.
     */
    val default =
      WWWFormURLEncoder(
        ArrayEncoding.Bracketed,
        BoolEncoding.Numeric,
        DateEncoding.ISO8601,
      )

    private val URI_ENCODE_COMPONENT_FIXES_REGEX = """\+|%21|%27|%28|%29|%7E""".toRegex(IGNORE_CASE)

    private fun encodeURIComponent(value: Any): String {
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

  enum class ArrayEncoding(
    val encode: (String) -> String,
  ) {

    Bracketed({ "$it[]" }),
    Unbracketed({ it }),
  }

  enum class BoolEncoding(
    val encode: (Boolean) -> String,
  ) {

    Numeric({ if (it) "1" else "0" }),
    Literal({ if (it) "true" else "false" }),
  }

  enum class DateEncoding(
    val encode: (Instant) -> String,
  ) {

    FractionalSecondsSinceEpoch(
      {
        (it.epochSecond + (it.nano / TimeUnit.SECONDS.toNanos(1).toDouble()))
          .toBigDecimal()
          .toPlainString()
      },
    ),
    MillisecondsSinceEpoch({ it.toEpochMilli().toString() }),
    ISO8601({ ISO_INSTANT.format(it) }),
  }

  override fun <T> encode(value: T): Source {
    val parameters = mapper.convertValue<Map<String, Any>>(value as Any)

    val buffer = Buffer()
    buffer.write(encodeQueryString(parameters).toByteArray(US_ASCII))
    return buffer
  }

  override fun encodeQueryString(parameters: Parameters): String =
    parameters
      .toSortedMap(compareBy { it })
      .flatMap { (key, value) ->
        encodeQueryComponent(key, value)
      }.joinToString("&")

  private fun encodeQueryComponent(
    key: String,
    value: Any?,
  ): List<String> =
    when (value) {
      null -> listOf(encodeURIComponent(key))

      is Map<*, *> ->
        value
          .toSortedMap(compareBy { it.toString() })
          .flatMap { (nestedKey, value) -> encodeQueryComponent("$key[$nestedKey]", value as Any) }

      is Iterable<*> ->
        value.flatMap { element ->
          encodeQueryComponent(
            arrayEncoding.encode(key),
            element as Any,
          )
        }

      is Array<*> ->
        value.flatMap { element ->
          encodeQueryComponent(
            arrayEncoding.encode(key),
            element as Any,
          )
        }

      is Instant ->
        listOf(encodeURIComponent(key) + "=" + encodeURIComponent(dateEncoding.encode(value)))

      is Boolean ->
        listOf(encodeURIComponent(key) + "=" + encodeURIComponent(boolEncoding.encode(value)))

      else -> listOf(encodeURIComponent(key) + "=" + encodeURIComponent("$value"))
    }

}
