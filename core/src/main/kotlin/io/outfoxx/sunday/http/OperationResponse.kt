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

import io.outfoxx.sunday.MediaType

/**
 * HTTP response that includes a parsed/decoded result.
 */
data class OperationResponse<T : Any>(
  val result: T,
  private val response: Response,
) : Response by response {

  /**
   * Retrieves all response header values matching the given name.
   */
  fun headers(name: String): Iterable<String> = response.headers.getAll(name)

  /**
   * Retrieves the first response header value matching the given name, or `null`.
   */
  fun header(name: String): String? = response.headers.getFirstOrNull(name)

  /**
   * Parsed `Content-Type` header value.
   */
  val contentType: MediaType?
    get() = response.contentType

}
