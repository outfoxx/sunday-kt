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

import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response

/**
 * Adapts the response returned by a native client call before Sunday's response and retry handling.
 * Intermediate responses consumed by native recovery are not delivered to this adapter.
 * The request describes the effective request associated with the returned response.
 * Consumed resources must be replaced before returning. A replacement that owns different I/O must
 * implement [Response.cancel] for it; cancellation follows the returned response into the next stage.
 */
interface ResponseAdapter {
  /**
   * Returns the adapted response while preserving its native type [TRes].
   * Implementations operate on the common wrapper contract and can serve multiple transports.
   * Errors stop the remaining adapters.
   */
  suspend fun <TRes> adapt(
    response: Response<TRes>,
    request: Request<*>,
  ): Response<TRes>

  /** Composes independent adapters in order, flattening existing chains. */
  fun chainTo(next: ResponseAdapter): ResponseAdapter = ResponseAdapters(unroll() + next.unroll())
}
