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

package io.outfoxx.sunday.problems

import io.outfoxx.sunday.http.Response
import org.zalando.problem.StatusType

/**
 * Container for non-standard HTTP statuses.
 */
data class NonStandardStatus(
  private val statusCode: Int,
  private val reasonPhrase: String?,
) : StatusType {

  constructor(response: Response) :
    this(response.statusCode, response.reasonPhrase)

  override fun getStatusCode() = statusCode

  override fun getReasonPhrase() = reasonPhrase
}
