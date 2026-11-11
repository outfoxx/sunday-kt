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

import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.problems.Problem
import io.outfoxx.sunday.security.SecurityBinding
import kotlin.reflect.KClass

/** Selects operation credentials while preserving the transport's validation, encoding, and execution paths. */
fun <Req, TRes> Transport<Req, TRes>.withSecurity(bindings: List<SecurityBinding>): Transport<Req, TRes> =
  if (bindings.isEmpty()) this else AuthenticatedTransport(this, bindings.toList())

private class AuthenticatedTransport<Req, TRes>(
  private val transport: Transport<Req, TRes>,
  private val bindings: List<SecurityBinding>,
) : Transport<Req, TRes>() {
  override val registeredProblemTypes get() = transport.registeredProblemTypes
  override val mediaTypeEncoders get() = transport.mediaTypeEncoders
  override val mediaTypeDecoders get() = transport.mediaTypeDecoders
  override val pathEncoders get() = transport.pathEncoders
  override val problemFactory get() = transport.problemFactory

  override fun registerProblem(
    typeId: String,
    problemType: KClass<out Problem>,
  ) = transport.registerProblem(typeId, problemType)

  override suspend fun <B : Any> transportRequest(
    method: Method,
    pathTemplate: String,
    pathParameters: Parameters?,
    queryParameters: Parameters?,
    body: B?,
    contentTypes: List<MediaType>?,
    acceptTypes: List<MediaType>?,
    headers: Parameters?,
    purpose: RequestPurpose,
    requestValidation: PayloadValidator<B>?,
    parameterValidation: ParameterValidator?,
  ): Request<Req> =
    transport.authorize(
      transport.transportRequest(
        method,
        pathTemplate,
        pathParameters,
        queryParameters,
        body,
        contentTypes,
        acceptTypes,
        headers,
        if (purpose == RequestPurpose.Export) RequestPurpose.Normal else purpose,
        requestValidation,
        parameterValidation,
      ),
      bindings,
      managed = purpose != RequestPurpose.Export,
    )

  override suspend fun authorize(
    request: Request<Req>,
    bindings: List<SecurityBinding>,
    managed: Boolean,
  ): Request<Req> = transport.authorize(request, bindings, managed)

  override suspend fun transportResponse(request: Request<Req>) = transport.transportResponse(request)

  override fun start(request: Request<Req>) = transport.start(request)

  override fun close() = transport.close()

  override fun close(cancelOutstandingRequests: Boolean) = transport.close(cancelOutstandingRequests)
}
