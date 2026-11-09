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

import kotlinx.io.Buffer

/** Events from streaming execution, retaining the native response type [TRes]. */
sealed interface ResponseEvent<out TRes> {
  /** Headers received before body consumption. Body chunks and trailers arrive in subsequent events. */
  data class Start<TRes>(
    val value: Response<TRes>,
  ) : ResponseEvent<TRes>

  /** A chunk of response body data owned by the consumer. */
  data class Data(
    val value: Buffer,
  ) : ResponseEvent<Nothing>

  /** Completed response trailers. */
  data class End(
    val trailers: Headers,
  ) : ResponseEvent<Nothing>
}
