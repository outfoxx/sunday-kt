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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit ordered composition of response adapters. */
class ResponseAdapters(
  adapters: List<ResponseAdapter>,
) : ResponseAdapter {
  internal val adapters = adapters.flatMap { it.unroll() }

  /** Creates a chain from individual adapters. */
  constructor(vararg adapters: ResponseAdapter) : this(adapters.toList())

  override suspend fun <TRes> adapt(
    response: Response<TRes>,
    request: Request<*>,
  ): Response<TRes> {
    var result = response
    try {
      for (adapter in adapters) {
        currentCoroutineContext().ensureActive()
        result.cancellable {
          // Record ownership before scope exit can throw cancellation and discard the return value.
          result = adapter.adapt(result, request)
        }
      }
      currentCoroutineContext().ensureActive()
      return result
    } catch (error: Throwable) {
      runCatching { result.body?.close() }.exceptionOrNull()?.let(error::addSuppressed)
      throw error
    }
  }
}

internal fun ResponseAdapter.unroll(): List<ResponseAdapter> = (this as? ResponseAdapters)?.adapters ?: listOf(this)
