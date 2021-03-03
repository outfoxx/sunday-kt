package io.outfoxx.sunday

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import io.outfoxx.sunday.http.Parameters
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter.ISO_INSTANT
import kotlin.text.Charsets.US_ASCII

class URLEncoder(
  private val arrayEncoding: ArrayEncoding = ArrayEncoding.Bracketed,
  private val boolEncoding: BoolEncoding = BoolEncoding.Numeric,
  private val dateEncoding: DateEncoding = DateEncoding.ISO8601,
  private val mapper: ObjectMapper = ObjectMapper().findAndRegisterModules()
) : MediaTypeEncoder {

  enum class ArrayEncoding(val encode: (String) -> String) {
    Bracketed({ "$it[]" }),
    Unbracketed({ it })
  }

  enum class BoolEncoding(val encode: (Boolean) -> String) {
    Numeric({ if (it) "1" else "0" }),
    Literal({ if (it) "true" else "false" })
  }

  enum class DateEncoding(val encode: (Instant) -> String) {
    SecondsSince1970({ "${it.epochSecond}" }),
    MillisecondsSince1970({ "${it.toEpochMilli()}" }),
    ISO8601({ ISO_INSTANT.format(it) })
  }

  override fun <T> encode(value: T): ByteArray {

    val parameters = mapper.convertValue<Map<String, Any>>(value as Any)

    return encodeQueryString(parameters).toByteArray(US_ASCII)
  }

  fun encodeQueryString(parameters: Parameters): String =
    parameters.keys.sorted()
      .flatMap { key ->
        encodeQueryComponent(key, parameters[key]!!)
      }
      .joinToString("&") { "${it.first}=${it.second}" }

  private fun encodeQueryComponent(key: String, value: Any): List<Pair<String, String>> =
    when (value) {
      is Map<*, *> -> value.flatMap { (nestedKey, value) -> encodeQueryComponent("$key[$nestedKey]", value as Any) }
      is List<*> -> value.flatMap { element -> encodeQueryComponent(arrayEncoding.encode(key), element as Any) }
      is Instant -> listOf(encode(key) to encode(dateEncoding.encode(value)))
      is Boolean -> listOf(encode(key) to encode(boolEncoding.encode(value)))
      else -> listOf(encode(key) to encode("$value"))
    }

  private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

}
