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

import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import java.net.URI

/** Attaches credential receipts without coupling native request representations to authentication. */
fun <TReq> Request<TReq>.withSecurityContext(context: SecurityContext?): Request<TReq> {
  if (securityContext == context) return this
  val original = if (this is SecuredRequest<TReq>) delegate else this
  return if (context == null && original.securityContext == null) original else SecuredRequest(original, context)
}

private class SecuredRequest<TReq>(
  val delegate: Request<TReq>,
  override val securityContext: SecurityContext?,
) : Request<TReq> by delegate {
  override fun copy(
    method: Method,
    uri: URI,
    headers: Headers,
  ): Request<TReq> = delegate.copy(method, uri, headers).withSecurityContext(securityContext)

  override fun copy(transport: TReq): Request<TReq> = delegate.copy(transport).withSecurityContext(securityContext)

  override fun withBody(body: StreamingBody?): Request<TReq> =
    delegate.withBody(body).withSecurityContext(securityContext)
}
