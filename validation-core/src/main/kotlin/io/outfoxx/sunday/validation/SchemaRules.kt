/*
 * Copyright 2026 Outfox, Inc.
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

package io.outfoxx.sunday.validation

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Native-constraint semantics shared by the javax and Jakarta Bean Validation adapters. */
class SchemaRules(
  private val requiredValue: Boolean = false,
  private val minLength: Int = -1,
  private val maxLength: Int = -1,
  patterns: List<String> = emptyList(),
  minimum: String = "",
  private val exclusiveMinimum: Boolean = false,
  maximum: String = "",
  private val exclusiveMaximum: Boolean = false,
  multipleOf: String = "",
  private val minItems: Int = -1,
  private val maxItems: Int = -1,
  private val uniqueItems: Boolean = false,
  private val numericElements: Boolean = false,
  private val restrictValues: Boolean = false,
  private val allowedStrings: List<String> = emptyList(),
  allowedNumbers: List<String> = emptyList(),
  private val allowedBooleans: List<Boolean> = emptyList(),
  private val format: String = "",
  private val stringElements: Boolean = false,
) {
  private val patterns = patterns.map(::Regex)
  private val minimum = minimum.takeIf(String::isNotEmpty)?.toBigDecimal()
  private val maximum = maximum.takeIf(String::isNotEmpty)?.toBigDecimal()
  private val multipleOf = multipleOf.takeIf(String::isNotEmpty)?.toBigDecimal()
  private val allowedNumbers = allowedNumbers.map(String::toBigDecimal)
  private val numeric = this.minimum != null || this.maximum != null || this.multipleOf != null

  init {
    require(this.multipleOf == null || this.multipleOf.signum() > 0) { "multipleOf must be positive" }
  }

  /** Reports stable reason codes, retaining item indexes for collection assertions. */
  fun failures(
    candidate: Any?,
    report: (reason: String, index: Int?) -> Unit,
  ) {
    val unwrapped = if (candidate is NominalValue<*>) candidate.value else candidate
    val value =
      if (unwrapped is JsonNode) {
        when {
          unwrapped.isNull -> null
          unwrapped.isTextual -> unwrapped.textValue()
          unwrapped.isBoolean -> unwrapped.booleanValue()
          unwrapped.isNumber -> unwrapped.decimalValue()
          unwrapped.isArray -> unwrapped.toList()
          else -> unwrapped
        }
      } else {
        unwrapped
      }
    if (value == null) {
      if (requiredValue) report("required", null)
      return
    }
    if (restrictValues && !isAllowed(value)) report("allowedValue", null)
    val collection = value as? Collection<*>
    if (minLength >= 0 || maxLength >= 0 || patterns.isNotEmpty()) {
      if (stringElements && collection != null) {
        collection.forEachIndexed { index, item -> if (item != null) stringFailures(item, index, report) }
      } else {
        stringFailures(value, null, report)
      }
    }
    val size = collection?.size ?: (value as? Map<*, *>)?.size ?: (value as? JsonNode)?.size()
    if (minItems >= 0 && size != null && size < minItems) report("minItems", null)
    if (maxItems >= 0 && size != null && size > maxItems) report("maxItems", null)
    if (uniqueItems && collection != null) {
      if (ModelGraph.cycle(collection) != null) {
        report("cycle", null)
      } else if (collection.toSet().size != collection.size) {
        report("uniqueItems", null)
      }
    }
    if (numeric) {
      if (numericElements && collection != null) {
        collection.forEachIndexed { index, item -> if (item != null) numberFailures(item, index, report) }
      } else {
        numberFailures(value, null, report)
      }
    }
  }

  private fun stringFailures(
    value: Any,
    index: Int?,
    report: (String, Int?) -> Unit,
  ) {
    val wire = wireString(value, format)
    val count = wire.codePointCount(0, wire.length)
    if (minLength >= 0 && count < minLength) report("minLength", index)
    if (maxLength >= 0 && count > maxLength) report("maxLength", index)
    patterns.forEach { if (!it.containsMatchIn(wire)) report("pattern", index) }
  }

  private fun isAllowed(value: Any): Boolean =
    when (value) {
      is Number ->
        value.toString().toBigDecimalOrNull()?.let { number -> allowedNumbers.any { it.compareTo(number) == 0 } } ==
          true
      is Boolean -> value in allowedBooleans
      else -> wireString(value, format) in allowedStrings
    }

  private fun numberFailures(
    value: Any,
    index: Int?,
    report: (String, Int?) -> Unit,
  ) {
    val number = value.toString().toBigDecimalOrNull()
    if (number == null) {
      report("nonFiniteNumber", index)
      return
    }
    minimum?.let {
      if (number < it || exclusiveMinimum && number.compareTo(it) == 0) report("minimum", index)
    }
    maximum?.let {
      if (number > it || exclusiveMaximum && number.compareTo(it) == 0) report("maximum", index)
    }
    multipleOf?.let { if (number.remainder(it).compareTo(BigDecimal.ZERO) != 0) report("multipleOf", index) }
  }

}

/** Keeps decoded scalar views and native string constraints on the same canonical representation. */
internal fun wireString(
  value: Any,
  format: String = "",
): String =
  when (value) {
    is ByteArray -> if (format == "byte") Base64.getEncoder().encodeToString(value) else value.toString()
    is LocalDate -> value.format(DateTimeFormatter.ISO_LOCAL_DATE)
    is LocalTime -> value.format(DateTimeFormatter.ISO_LOCAL_TIME)
    is LocalDateTime -> value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    is OffsetDateTime -> value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    is JsonNode -> if (value.isTextual) value.textValue() else value.toString()
    else -> value.toString()
  }
