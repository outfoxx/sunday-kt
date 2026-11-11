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

import io.outfoxx.sunday.ResponseAdapter
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.getAll
import io.outfoxx.sunday.http.getFirstOrNull

/** Opt-in stop policy for explicit bearer rejection, without acquisition, invalidation, refresh, or replay. */
class BearerTokenRejectionAdapter : ResponseAdapter {
  /** Leaves unrelated responses and bodies untouched. Compose after provider feedback when both are installed. */
  override suspend fun <TRes> adapt(
    response: Response<TRes>,
    request: Request<*>,
  ): Response<TRes> {
    if (response.statusCode == 401 &&
      BearerChallenge.token(request.headers.getFirstOrNull("Authorization")) != null &&
      BearerChallenge.isInvalidToken(response.headers.getAll("WWW-Authenticate").joinToString(","))
    ) {
      throw TokenRejectedException()
    }
    return response
  }
}
