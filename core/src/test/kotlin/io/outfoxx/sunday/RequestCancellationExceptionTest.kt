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

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

class RequestCancellationExceptionTest {
  class Stop(
    message: String,
  ) : RuntimeException(message),
    RequestCancellationException

  @Test
  fun `nested signals are detected without removing diagnostic wrappers`() {
    for (signal in listOf(Stop("stop"), CancellationException("cancel"))) {
      val diagnostic = IOException("outer", IllegalStateException("middle", signal))
      assertTrue(diagnostic.isRequestCancelled)
      assertSame(signal, diagnostic.causes().last())
      assertSame(diagnostic, diagnostic.unwrapRequestException())
      assertSame(diagnostic, CompletionException(ExecutionException(diagnostic)).unwrapRequestException())
    }
    assertFalse(IOException("ordinary", IllegalStateException("ordinary")).isRequestCancelled)
  }

  class CompletionStop(
    cause: Throwable,
  ) : CompletionException(cause),
    RequestCancellationException

  class ExecutionStop(
    cause: Throwable,
  ) : ExecutionException(cause),
    RequestCancellationException

  @Test
  fun `unwrapping retains marked asynchronous wrappers and their unmarked causes`() {
    for (stop in listOf(CompletionStop(IOException("cause")), ExecutionStop(IOException("cause")))) {
      for (wrapped in listOf(stop, CompletionException(ExecutionException(stop)))) {
        val result = wrapped.unwrapRequestException()
        assertSame(stop, result)
        assertTrue(result.isRequestCancelled)
        assertSame(stop.cause, result.cause)
      }
    }
    val ordinary = IOException("ordinary")
    assertSame(ordinary, CompletionException(ExecutionException(ordinary)).unwrapRequestException())
  }

  @Test
  fun `cyclic cause chains are visited once with or without a stop signal`() {
    for (last in listOf(Stop("stop"), IllegalStateException("ordinary"))) {
      val first = IOException("outer", last)
      last.initCause(first)
      val causes = first.causes().toList()
      assertEquals(2, causes.size)
      assertSame(first, causes[0])
      assertSame(last, causes[1])
      assertEquals(last is RequestCancellationException, first.isRequestCancelled)
      assertSame(first, first.unwrapRequestException())
    }
  }
}
