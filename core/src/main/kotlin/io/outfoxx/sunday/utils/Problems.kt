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

package io.outfoxx.sunday.utils

import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.problems.NonStandardStatus
import org.zalando.problem.Problem
import org.zalando.problem.Status
import org.zalando.problem.ThrowableProblem

internal object Problems {

  fun forResponse(response: Response): ThrowableProblem = forStatus(response.statusCode, response.reasonPhrase)

  fun forStatus(
    statusCode: Int,
    reasonPhrase: String?,
  ): ThrowableProblem {
    val status =
      try {
        Status.valueOf(statusCode)
      } catch (ignored: IllegalArgumentException) {
        NonStandardStatus(statusCode, reasonPhrase ?: "Unknown Status")
      }
    return Problem.valueOf(status)
  }

}
