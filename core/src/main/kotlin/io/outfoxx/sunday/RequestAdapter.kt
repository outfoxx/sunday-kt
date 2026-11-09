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

/**
 * Adapts a request once before each Sunday-managed native client call, or once for request export.
 * Native recovery may retransmit that adapted request without invoking this adapter again.
 * Consumed resources must be replaced before returning.
 */
interface RequestAdapter {
  /**
   * Returns the adapted request while preserving its native type [TReq].
   * Implementations operate on the common wrapper contract and can serve multiple transports.
   * Errors stop the remaining adapters.
   */
  suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq>

  /** Composes independent adapters in order, flattening existing chains. */
  fun chainTo(next: RequestAdapter): RequestAdapter = RequestAdapters(unroll() + next.unroll())
}
