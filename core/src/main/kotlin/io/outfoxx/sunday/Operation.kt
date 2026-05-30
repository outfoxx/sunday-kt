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
import io.outfoxx.sunday.http.OperationResponse
import io.outfoxx.sunday.http.Parameters
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.problems.Problem
import kotlinx.coroutines.CancellationException
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Describes a generated HTTP operation request.
 */
data class OperationSpec<B : Any>(
  val method: Method,
  val pathTemplate: String,
  val pathParameters: Parameters? = null,
  val queryParameters: Parameters? = null,
  val body: B? = null,
  val contentTypes: List<MediaType>? = null,
  val acceptTypes: List<MediaType>? = null,
  val headers: Parameters? = null,
)

/**
 * Describes problems that should be translated into a null response value.
 */
data class NullifySpec(
  val statuses: List<Int> = listOf(404),
  val problemTypes: List<KClass<out Problem>> = emptyList(),
)

/**
 * A generated operation that can be executed or converted into a native request.
 */
class Operation<B : Any, R : Any, Req : Request>(
  private val transport: Transport<Req>,
  val spec: OperationSpec<B>,
  private val resultType: KType,
) {

  /**
   * Builds a native request without executing it.
   */
  suspend fun transportRequest(): Req =
    transport.transportRequest(
      spec.method,
      spec.pathTemplate,
      spec.pathParameters,
      spec.queryParameters,
      spec.body,
      spec.contentTypes,
      spec.acceptTypes,
      spec.headers,
    )

  /**
   * Executes the operation and returns the native transport response.
   */
  suspend fun transportResponse() = transport.transportResponse(transportRequest())

  /**
   * Executes the operation and decodes the response value.
   */
  suspend fun execute(): R =
    transport.result(
      spec.method,
      spec.pathTemplate,
      spec.pathParameters,
      spec.queryParameters,
      spec.body,
      spec.contentTypes,
      spec.acceptTypes,
      spec.headers,
      resultType,
    )

  /**
   * Executes the operation and returns the decoded value with the HTTP response.
   */
  suspend fun response(): OperationResponse<R> =
    transport.response(
      spec.method,
      spec.pathTemplate,
      spec.pathParameters,
      spec.queryParameters,
      spec.body,
      spec.contentTypes,
      spec.acceptTypes,
      spec.headers,
      resultType,
    )

}

/**
 * A generated operation with a streaming request body.
 */
typealias StreamingOperation<R, Req> = Operation<StreamingBody, R, Req>

/**
 * A generated operation that can execute select problems as null responses.
 */
class NullableOperation<B : Any, R : Any, Req : Request>(
  private val transport: Transport<Req>,
  val spec: OperationSpec<B>,
  private val resultType: KType,
  val nullify: NullifySpec,
) {

  private val operation = Operation<B, R, Req>(transport, spec, resultType)

  /**
   * Builds a native request without executing it.
   */
  suspend fun transportRequest(): Req = operation.transportRequest()

  /**
   * Executes the operation and returns the native transport response.
   */
  suspend fun transportResponse() = operation.transportResponse()

  /**
   * Executes the operation and decodes the response value.
   */
  suspend fun execute(): R = operation.execute()

  /**
   * Executes the operation and translates configured problems into null.
   */
  suspend fun executeOrNull(): R? =
    try {
      execute()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (problem: Problem) {
      if (matchesNullify(problem)) {
        null
      } else {
        throw problem
      }
    }

  /**
   * Executes the operation and returns the decoded value with the HTTP response.
   */
  suspend fun response(): OperationResponse<R> = operation.response()

  private fun matchesNullify(problem: Problem): Boolean =
    problemStatus(problem)?.let(nullify.statuses::contains) == true ||
      nullify.problemTypes.any { problemType -> problemType.isInstance(problem) }

  private fun problemStatus(problem: Problem): Int? =
    transport
      .problemFactory
      .adapter()
      .getStatus(problem)
      ?.code

}

/**
 * Creates an operation using the response type as the decoded result type.
 */
inline fun <B : Any, reified R : Any, Req : Request> Transport<Req>.operation(
  spec: OperationSpec<B>,
): Operation<B, R, Req> = Operation(this, spec, typeOf<R>())

/**
 * Creates a nullable operation using the response type as the decoded result type.
 */
inline fun <B : Any, reified R : Any, Req : Request> Transport<Req>.nullableOperation(
  spec: OperationSpec<B>,
  nullify: NullifySpec,
): NullableOperation<B, R, Req> = NullableOperation(this, spec, typeOf<R>(), nullify)
