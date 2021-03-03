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

import com.github.hal4j.uritemplate.URIBuilder
import io.outfoxx.sunday.http.Parameters

class URITemplate(
  private val templateBuilder: URIBuilder,
  private val parameters: Parameters
) {

  constructor(template: String, parameters: Parameters) :
    this(URIBuilder.basedOn(template), parameters)

  fun resolve(relative: String? = null, parameters: Parameters? = null): URIBuilder {

    val template =
      if (relative != null)
        templateBuilder.resolve(relative)
      else
        templateBuilder.asTemplate()

    val allParameters =
      if (parameters != null)
        this.parameters + parameters
      else
        this.parameters

    return template.expand(allParameters).toBuilder()
  }
}
