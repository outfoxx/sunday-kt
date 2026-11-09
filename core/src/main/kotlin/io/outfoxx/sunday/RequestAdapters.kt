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
import io.outfoxx.sunday.security.withSecurityContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit ordered composition of request adapters. */
class RequestAdapters(
  adapters: List<RequestAdapter>,
) : RequestAdapter {
  internal val adapters = adapters.flatMap { it.unroll() }

  /** Creates a chain from individual adapters. */
  constructor(vararg adapters: RequestAdapter) : this(adapters.toList())

  override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
    var result = request
    for (adapter in adapters) {
      result = result.adaptedBy { adapter.adapt(it) }
    }
    currentCoroutineContext().ensureActive()
    return result
  }
}

internal fun RequestAdapter.unroll(): List<RequestAdapter> = (this as? RequestAdapters)?.adapters ?: listOf(this)

/** Applies portable and native adapters with the same cancellation and credential-metadata rules. */
internal suspend fun <TReq> Request<TReq>.adaptedBy(adapter: suspend (Request<TReq>) -> Request<TReq>): Request<TReq> {
  currentCoroutineContext().ensureActive()
  val adapted = adapter(this)
  val result =
    if (adapted.securityContext == null && securityContext != null) {
      adapted.withSecurityContext(securityContext)
    } else {
      adapted
    }
  currentCoroutineContext().ensureActive()
  return result
}
