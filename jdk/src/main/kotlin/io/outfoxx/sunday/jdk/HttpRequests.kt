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

package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.http.Headers
import java.net.http.HttpRequest

internal fun HttpRequest.Builder.headers(headers: Headers) =
  apply {
    headers.forEach { header(it.first, it.second) }
  }

fun HttpRequest.copyToBuilder(includeHeaders: Boolean = true): HttpRequest.Builder {
  val builder = HttpRequest.newBuilder()
  builder.uri(uri())
  builder.expectContinue(expectContinue())

  version().ifPresent(builder::version)
  timeout().ifPresent(builder::timeout)

  if (includeHeaders) {
    headers().map().forEach { (name, values) -> values.forEach { builder.header(name, it) } }
  }

  val method = method()
  bodyPublisher().ifPresentOrElse(
    { builder.method(method, it) },
    {
      when (method) {
        "GET" -> builder.GET()
        "DELETE" -> builder.DELETE()
        else -> builder.method(method, HttpRequest.BodyPublishers.noBody())
      }
    },
  )

  return builder
}
