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

import java.util.concurrent.atomic.AtomicBoolean

/** One authentication recovery per invocation, shared by every connection of an event subscription. */
class AuthenticationRecoveryBudget {
  private val used = AtomicBoolean(false)

  /** Permits at most one bodyless safe-method retry after an explicit invalid-token response. */
  fun shouldRetry(
    request: io.outfoxx.sunday.http.Request<*>,
    response: io.outfoxx.sunday.http.Response<*>,
  ): Boolean =
    response.statusCode == 401 &&
      !request.hasBody &&
      request.isBodyRepeatable &&
      request.method in
      setOf(
        io.outfoxx.sunday.http.Method.Get,
        io.outfoxx.sunday.http.Method.Head,
        io.outfoxx.sunday.http.Method.Options,
      ) &&
      BearerChallenge.token(request.headers.firstOrNull { it.first.equals("Authorization", true) }?.second) != null &&
      BearerChallenge.isInvalidToken(
        response.headers
          .filter { it.first.equals("WWW-Authenticate", true) }
          .joinToString(",") { it.second },
      ) &&
      consume()

  internal fun consume(): Boolean = used.compareAndSet(false, true)
}
