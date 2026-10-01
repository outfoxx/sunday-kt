/*
 * Copyright 2026 Outfox, Inc.
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

package io.outfoxx.sunday.client.quarkus

import io.smallrye.mutiny.Uni
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class ClientInvocationTest {
  @Test
  fun `late responses and acquisition from expired attempts cannot classify the active failure`() {
    val invocation = ClientInvocation()
    lateinit var expired: ClientInvocation.Attempt
    val pending = CompletableFuture<String>()
    invocation.attemptStage { attempt ->
      expired = attempt
      attempt.startRequest("GET", false)
      pending
    }
    val failure = IllegalStateException("active upstream failed")
    val observed =
      assertThrows(IllegalStateException::class.java) {
        invocation.attempt { active ->
          active.startRequest("GET", false)
          assertTrue(active.acquired("current"))
          expired.received(403, false)
          assertEquals(false, expired.acquired("stale"))
          assertEquals("current", active.credential)
          pending.completeExceptionally(IllegalArgumentException("expired upstream failed"))
          throw failure
        }
      }
    assertSame(failure, observed)
  }

  @Test
  fun `one recovery retains exactly the credential rejected by its own attempt`() {
    val invocation = ClientInvocation(true)
    val credential = Any()
    lateinit var rejected: ClientInvocation.Attempt
    assertThrows(ClientInvocation.Recoverable::class.java) {
      invocation.attempt { attempt ->
        rejected = attempt
        attempt.startRequest("GET", false)
        attempt.acquired(credential)
        attempt.received(401, true)
        throw IllegalArgumentException("first unauthorized")
      }
    }
    assertThrows(ClientInvocation.Stopped::class.java) {
      invocation.attempt { active ->
        active.startRequest("GET", false)
        assertSame(credential, active.rejectedCredential)
        assertEquals(2, active.number)
        rejected.received(403, false)
        active.received(401, true)
        throw IllegalArgumentException("still unauthorized")
      }
    }
    assertThrows(ClientInvocation.Stopped::class.java) { invocation.attempt { error("Must not execute") } }
  }

  @Test
  fun `cancelling the public completion stage cancels the native transport future`() {
    val transport = CompletableFuture<String>()
    val operation =
      ClientInvocation
        .executeStage { invocation ->
          invocation.attemptStage { transport }
        }.toCompletableFuture()
    assertTrue(operation.cancel(true))
    assertTrue(transport.isCancelled)
  }

  @Test
  fun `cancelling the public reactive subscription cancels its native transport`() {
    val cancelled = AtomicBoolean()
    val transport =
      Uni
        .createFrom()
        .nothing<String>()
        .onCancellation()
        .invoke { cancelled.set(true) }
    val operation = ClientInvocation.executeUni { invocation -> invocation.attemptUni { transport } }
    val subscription = operation.subscribe().with({ error("Unexpected value") }, { error("Unexpected failure") })
    subscription.cancel()
    assertTrue(cancelled.get())
  }

  @Test
  fun `repeated deferred executions receive independent budgets and restore original failures`() {
    val original = IllegalArgumentException("native problem")
    val attempts = AtomicBoolean()
    val operation =
      ClientInvocation.executeUni(true) { invocation ->
        invocation.attemptUni<String> { attempt ->
          attempt.startRequest("GET", false)
          attempt.received(401, true)
          attempts.set(true)
          Uni.createFrom().failure(original)
        }
      }
    repeat(2) {
      assertSame(original, assertThrows(IllegalArgumentException::class.java) { operation.await().indefinitely() })
    }
    assertTrue(attempts.get())
  }

  @Test
  fun `terminal authentication failures retain original exceptions without further transport work`() {
    val original = IllegalArgumentException("forbidden")
    var attempts = 0
    val invocation = ClientInvocation()
    assertThrows(ClientInvocation.Stopped::class.java) {
      invocation.attempt { attempt ->
        attempts++
        attempt.startRequest("GET", false)
        attempt.received(403, false)
        throw original
      }
    }
    assertThrows(ClientInvocation.Stopped::class.java) { invocation.attempt { attempts++ } }
    assertEquals(1, attempts)
  }
}
