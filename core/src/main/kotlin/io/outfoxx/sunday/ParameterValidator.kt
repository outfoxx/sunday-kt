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

import kotlinx.coroutines.CancellationException

/** Validates the captured typed parameters before a request is encoded, including bodyless requests. */
fun interface ParameterValidator {

  /** Throws the native validation error if any participating parameter is invalid. */
  fun validate()

  /** Runs validation with a terminal request-failure wrapper while preserving cancellation. */
  fun validateRequest() {
    try {
      validate()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      throw Failure(error)
    }
  }

  /** A parameter cannot be encoded; event sources must not reconnect without a new operation. */
  class Failure(
    cause: Throwable,
  ) : IllegalArgumentException("Request parameter validation failed", cause)

}
