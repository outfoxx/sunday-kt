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

package io.outfoxx.sunday

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.hal4j.uritemplate.URIBuilder
import com.github.hal4j.uritemplate.URITemplate
import io.outfoxx.sunday.http.Parameters

class URITemplate(
  private val template: String,
  private val parameters: Parameters = mapOf(),
) {

  fun resolve(relative: String? = null, parameters: Parameters? = null): URIBuilder {

    val template = URITemplate(join(template, relative))

    val allParameters =
      if (parameters != null)
        this.parameters + parameters
      else
        this.parameters

    val allStringParameters =
      allParameters.mapValues { entry ->
        when (val value = entry.value) {
          is Enum<*> -> enumName(value)
          else -> value.toString()
        }
      }

    return template.expand(allStringParameters).toBuilder()
  }

  private fun <E : Enum<E>> enumName(value: Enum<E>) =
    value.javaClass.getField(value.name).getAnnotation(JsonProperty::class.java)?.value
      ?: value.name

  private fun join(base: String, relative: String?) =
    if (relative != null) {
      if (base.endsWith("/") && relative.startsWith("/")) {
        base + relative.removePrefix("/")
      } else if (base.endsWith("/") || relative.startsWith("/")) {
        base + relative
      } else {
        "$base/$relative"
      }
    } else {
      base
    }

}
