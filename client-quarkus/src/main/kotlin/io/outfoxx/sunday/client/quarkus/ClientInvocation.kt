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

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.subscription.Cancellable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Invocation-local authentication recovery shared with the native retry interceptor.
 *
 * The generated policy method is the sole replay owner. Its abortOn list must include
 * [Stopped] and [CancellationException]; its transport method retains timeout, breaker,
 * and rate-limit policies so terminal failures never count as additional attempts.
 */
class ClientInvocation(
  private val authenticationOnly: Boolean = false,
) {
  private var current: Attempt? = null
  private var recovered = false
  private var recoveryPending = false
  private var stopped: Throwable? = null

  /** Identifies one transport attempt so late callbacks cannot affect a newer attempt's budget. */
  inner class Attempt internal constructor(
    /** The one-based transport attempt number within this invocation. */
    val number: Int,
    internal val rejectedCredential: Any?,
  ) {
    private val finished = AtomicBoolean()
    private val acquisition = AtomicReference<Cancellable?>()
    internal val isFinished: Boolean get() = finished.get()
    internal var credential: Any? = null
    internal var safe = false
    internal var status = 0
    internal var invalidToken = false

    /** Records this transport's replayability before acquiring credentials. */
    fun startRequest(
      method: String,
      hasEntity: Boolean,
    ) = start(this, method, hasEntity)

    /** Retains a challenge only while this is the invocation's active attempt. */
    fun received(
      status: Int,
      invalidToken: Boolean,
    ) = receive(this, status, invalidToken)

    internal fun acquired(credential: Any): Boolean = acquire(this, credential)

    // Registration can race synchronous completion or cancellation of the transport.
    internal fun acquiring(subscription: Cancellable) {
      acquisition.getAndSet(subscription)?.cancel()
      if (finished.get()) acquisition.getAndSet(null)?.cancel()
    }

    internal fun finish() {
      finished.set(true)
      acquisition.getAndSet(null)?.cancel()
    }
  }

  private fun nextAttempt(): Attempt {
    val (previous, next) =
      synchronized(this) {
        stopped?.let { throw Stopped(it) }
        val previous = current
        val rejected = if (recoveryPending) previous?.credential else null
        recoveryPending = false
        val next = Attempt((previous?.number ?: 0) + 1, rejected)
        current = next
        previous to next
      }
    previous?.finish()
    return next
  }

  @Synchronized
  private fun start(
    attempt: Attempt,
    method: String,
    hasEntity: Boolean,
  ) {
    stopped?.let { throw Stopped(it) }
    if (current !== attempt || attempt.isFinished) throw CancellationException("Transport attempt expired")
    attempt.safe = !hasEntity && method.uppercase() in setOf("GET", "HEAD", "OPTIONS")
  }

  @Synchronized
  private fun receive(
    attempt: Attempt,
    status: Int,
    invalidToken: Boolean,
  ) {
    if (current !== attempt || attempt.isFinished) return
    attempt.status = status
    attempt.invalidToken = invalidToken
  }

  @Synchronized
  private fun acquire(
    attempt: Attempt,
    credential: Any,
  ): Boolean {
    if (current !== attempt || attempt.isFinished || stopped != null) return false
    attempt.credential = credential
    return true
  }

  /** Adapts a synchronous attempt to native retry without changing ordinary typed failures. */
  fun <T> attempt(action: (Attempt) -> T): T {
    val attempt = nextAttempt()
    return try {
      evaluate(attempt, action)
    } finally {
      attempt.finish()
    }
  }

  private fun <T> evaluate(
    attempt: Attempt,
    action: (Attempt) -> T,
  ): T =
    try {
      action(attempt)
    } catch (error: Throwable) {
      try {
        throw failure(attempt, error)
      } finally {
        attempt.finish()
      }
    }

  /** Adapts a lazy attempt; each subscription remains inside its owning invocation. */
  fun <T> attemptUni(action: (Attempt) -> Uni<T>): Uni<T> =
    Uni.createFrom().deferred {
      val attempt = nextAttempt()
      evaluate(attempt, action)
        .onFailure()
        .transform { failure(attempt, it) }
        .onTermination()
        .invoke { attempt.finish() }
    }

  /** Validates one streamed attempt without replaying a subscription. */
  fun <T> attemptMulti(action: (Attempt) -> Multi<T>): Multi<T> =
    Multi.createFrom().deferred {
      val attempt = nextAttempt()
      evaluate(attempt, action)
        .onFailure()
        .transform { failure(attempt, it) }
        .onTermination()
        .invoke { attempt.finish() }
    }

  /** Adapts completion-stage failures while preserving their original causes at the public boundary. */
  fun <T> attemptStage(action: (Attempt) -> CompletionStage<T>): CompletionStage<T> {
    val attempt = nextAttempt()
    return mapFailure(evaluate(attempt, action), { failure(attempt, it) }, attempt::finish)
  }

  /** Retains the same budget across coroutine suspension without thread-local state. */
  suspend fun <T> attemptSuspend(action: suspend (Attempt) -> T): T {
    val attempt = nextAttempt()
    return try {
      action(attempt)
    } catch (error: Throwable) {
      throw failure(attempt, error)
    } finally {
      attempt.finish()
    }
  }

  @Synchronized
  private fun failure(
    attempt: Attempt,
    error: Throwable,
  ): Throwable {
    val original = unwrap(error)
    if (current !== attempt ||
      error is Stopped ||
      original is CancellationException ||
      original !is Exception
    ) {
      return error
    }
    if (attempt.status != 401 && attempt.status != 403) return original
    if (attempt.status == 401 && attempt.safe && attempt.invalidToken && !recovered) {
      recovered = true
      recoveryPending = true
      return if (authenticationOnly) Recoverable(original) else original
    }
    stopped = original
    return Stopped(original)
  }

  /** Internal abort marker removed before the failure reaches application code. */
  class Stopped(
    cause: Throwable,
  ) : RuntimeException("Authentication recovery stopped", cause, false, false)

  /** Selects authentication-only retry when the operation has no explicit retry policy. */
  class Recoverable(
    cause: Throwable,
  ) : RuntimeException("Authentication recovery requested", cause, false, false)

  companion object {
    /** Allocates a budget outside native retry and restores the application's original failure. */
    fun <T> execute(
      authenticationOnly: Boolean = false,
      action: (ClientInvocation) -> T,
    ): T =
      try {
        action(ClientInvocation(authenticationOnly))
      } catch (error: Throwable) {
        throw unwrap(error)
      }

    /** Allocates a fresh budget for every subscription, including repeated deferred executions. */
    fun <T> executeUni(
      authenticationOnly: Boolean = false,
      action: (ClientInvocation) -> Uni<T>,
    ): Uni<T> =
      Uni.createFrom().deferred {
        execute(authenticationOnly, action).onFailure().transform(::unwrap)
      }

    /** Allocates a new budget for each streamed subscription without adding replay. */
    fun <T> executeMulti(
      authenticationOnly: Boolean = false,
      action: (ClientInvocation) -> Multi<T>,
    ): Multi<T> =
      Multi.createFrom().deferred {
        execute(authenticationOnly, action).onFailure().transform(::unwrap)
      }

    /** Allocates a budget once before starting a completion-stage invocation. */
    fun <T> executeStage(
      authenticationOnly: Boolean = false,
      action: (ClientInvocation) -> CompletionStage<T>,
    ): CompletionStage<T> = mapFailure(execute(authenticationOnly, action), ::unwrap)

    /** Allocates a budget in the caller's coroutine and propagates cancellation unchanged. */
    suspend fun <T> executeSuspend(
      authenticationOnly: Boolean = false,
      action: suspend (ClientInvocation) -> T,
    ): T =
      try {
        action(ClientInvocation(authenticationOnly))
      } catch (error: Throwable) {
        throw unwrap(error)
      }

    private fun <T> mapFailure(
      stage: CompletionStage<T>,
      transform: (Throwable) -> Throwable,
      completed: () -> Unit = {},
    ): CompletionStage<T> {
      val upstream = stage.toCompletableFuture()
      val result =
        object : CompletableFuture<T>() {
          override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val cancelled = super.cancel(mayInterruptIfRunning)
            if (cancelled) {
              completed()
              upstream.cancel(mayInterruptIfRunning)
            }
            return cancelled
          }
        }
      stage.whenComplete { value, error ->
        try {
          if (error == null) result.complete(value) else result.completeExceptionally(transform(error))
        } finally {
          completed()
        }
      }
      return result
    }

    private fun unwrap(error: Throwable): Throwable =
      if ((error is CompletionException || error is Stopped || error is Recoverable) && error.cause != null) {
        unwrap(error.cause!!)
      } else {
        error
      }
  }
}
