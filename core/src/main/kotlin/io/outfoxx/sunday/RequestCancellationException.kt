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
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Marks an exception as an unconditional stop signal, without cancelling the caller's coroutine.
 * A signal anywhere in the cause chain prevents retry or reconnect. Diagnostic wrappers, native
 * wrappers and coroutine recovery copies may retain the signal as a cause.
 * This does not give adapters visibility into intermediate native recovery responses.
 */
interface RequestCancellationException

/** Iterates this exception and its causes, stopping before an instance is visited twice. */
fun Throwable.causes(): Sequence<Throwable> =
  sequence {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this@causes
    while (current != null && seen.add(current)) {
      yield(current)
      current = current.cause
    }
  }

/** True when this exception or any cause signals request or coroutine cancellation. */
val Throwable.isRequestCancelled: Boolean
  get() = causes().any { it is RequestCancellationException || it is CancellationException }

/** Removes leading asynchronous-result wrappers, stopping at a marked wrapper to preserve its stop signal. */
fun Throwable.unwrapRequestException(): Throwable =
  causes().firstOrNull {
    it is RequestCancellationException || (it !is CompletionException && it !is ExecutionException)
  } ?: this
