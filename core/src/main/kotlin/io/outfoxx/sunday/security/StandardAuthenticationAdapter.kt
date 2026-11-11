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

package io.outfoxx.sunday.security

import io.outfoxx.sunday.RequestAdapter
import io.outfoxx.sunday.ResponseAdapter
import io.outfoxx.sunday.ResponseAdapters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response

/** Opt-in credential attachment, rejection feedback, and terminal rejection cancellation.
 * Supply TokenManager explicitly for caching; supplied providers retain their lifecycle ownership.
 */
class StandardAuthenticationAdapter(
  providers: Map<String, TokenProvider>,
) : RequestAdapter,
  ResponseAdapter {
  private val authentication = AuthenticationAdapter(providers)
  private val responses = ResponseAdapters(listOf(authentication, BearerTokenRejectionAdapter()))

  /** Attaches selected credentials without acquiring or caching any additional tokens. */
  override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> = authentication.adapt(request)

  /** Reports rejection before stopping the exchange, without reading bodies or replaying. */
  override suspend fun <TRes> adapt(
    response: Response<TRes>,
    request: Request<*>,
  ): Response<TRes> {
    if (request.securityContext?.bindings.isNullOrEmpty()) return response
    return responses.adapt(response, request)
  }
}
