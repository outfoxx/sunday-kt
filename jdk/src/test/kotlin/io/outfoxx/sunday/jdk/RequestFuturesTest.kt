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

package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.RequestCancellationException
import io.outfoxx.sunday.causes
import io.outfoxx.sunday.isRequestCancelled
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class RequestFuturesTest {
  class Stop(
    cause: Throwable,
  ) : CompletionException(cause),
    RequestCancellationException

  @Test
  fun `immediate and suspended completion preserve a marked wrapper`() =
    runTest {
      for (immediate in listOf(false, true)) {
        val stop = Stop(IOException("native failure"))
        val future = CompletableFuture<String>()
        if (immediate) future.completeExceptionally(stop)
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { future.requestAwait() } }
        if (!immediate) future.completeExceptionally(stop)
        val failure = result.await().exceptionOrNull()!!
        assertTrue(failure.isRequestCancelled)
        assertTrue(failure.causes().any { it === stop })
      }
    }

  @Test
  fun `cancellation cancels the original future`() =
    runTest {
      val future = CompletableFuture<String>()
      val result = async(start = CoroutineStart.UNDISPATCHED) { future.requestAwait() }
      result.cancel()
      result.join()
      assertTrue(future.isCancelled)
    }

  @Test
  fun `a delivered resource belongs to cleanup if cancellation wins dispatch`() =
    runTest {
      val resource = Any()
      val future = CompletableFuture<Any>()
      var closed: Any? = null
      var closes = 0
      val result =
        async(start = CoroutineStart.UNDISPATCHED) {
          future.requestAwait {
            closed = it
            closes++
          }
        }
      future.complete(resource)
      result.cancel()
      result.join()
      assertSame(resource, closed)
      assertEquals(1, closes)
    }
}
