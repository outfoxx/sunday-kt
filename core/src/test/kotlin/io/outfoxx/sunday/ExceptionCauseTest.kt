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

import io.outfoxx.sunday.security.AuthorizationRequiredException
import io.outfoxx.sunday.security.TokenProviderException
import io.outfoxx.sunday.security.TokenRejectedException
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ExceptionCauseTest {
  @Test
  fun `runtime exception constructors retain causes and nested stop signals`() {
    val stop = object : RuntimeException("stop"), RequestCancellationException {}
    val cause = IOException("native boundary", stop)
    val failures =
      listOf(
        TokenProviderException(cause = cause),
        AuthorizationRequiredException(cause),
        TokenRejectedException(cause),
        SundayError("failure", cause = cause),
        SundayError(SundayError.Reason.InvalidBaseUri, cause = cause),
        EventSourceError(EventSourceError.Reason.InvalidState, cause),
        ParameterValidator.Failure(cause),
      )
    for (failure in failures) {
      assertSame(cause, failure.cause, failure.javaClass.name)
      assertTrue(failure.isRequestCancelled, failure.javaClass.name)
    }
  }

  @Test
  fun `parameter validation preserves a wrapped stop signal`() {
    val stop = TokenRejectedException()
    val failure = runCatching { ParameterValidator { throw stop }.validateRequest() }.exceptionOrNull()!!
    assertTrue(failure is ParameterValidator.Failure)
    assertSame(stop, failure.cause)
    assertTrue(failure.isRequestCancelled)
  }
}
