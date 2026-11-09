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
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.copy

/** Adds selected operation credentials and reports rejections to their owners, without adding a cache. */
class AuthenticationAdapter(
  private val providers: Map<String, TokenProvider>,
) : RequestAdapter,
  ResponseAdapter {
  override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
    val context = request.securityContext ?: return request
    val credentials = RequestSecurity(context.bindings, providers).authorize(request.uri, request.headers)
    return request
      .copy(
        uri = credentials.uri,
        headers = credentials.headers,
      ).withSecurityContext(SecurityContext(context.bindings, credentials.leases))
  }

  override suspend fun <TRes> adapt(
    response: Response<TRes>,
    request: Request<*>,
  ): Response<TRes> {
    val context = request.securityContext ?: return response
    val security = RequestSecurity(context.bindings, providers)
    security.reject(RequestSecurity.Credentials(request.uri, request.headers, context.leases), response)
    val redacted = security.redact(request.uri, request.headers)
    return response.copy(
      request = request.copy(uri = redacted.uri, headers = redacted.headers).withSecurityContext(null),
    )
  }
}
