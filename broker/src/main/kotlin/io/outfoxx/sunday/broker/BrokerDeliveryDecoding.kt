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

package io.outfoxx.sunday.broker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.KType

/**
 * Decodes each delivery, allowing a handled decoding failure to be skipped.
 *
 * Only decoding is covered by the recovery policy. Transport failures, downstream
 * processing failures, cancellation and JVM errors are not recoverable. Cancellation
 * and JVM errors wrapped by a codec are unwrapped and rethrown.
 */
fun <T : Any> Flow<BrokerRawDelivery>.decodeDeliveries(
  spec: BrokerConsumeSpec,
  type: KType,
  codec: BrokerMessageCodec = BrokerMessageCodec(),
  onDecodeFailure: BrokerDecodeFailureHandler = BrokerDecodeFailureHandler.Rethrow,
): Flow<BrokerDelivery<T>> =
  transform { delivery ->
    currentCoroutineContext().ensureActive()
    val body =
      try {
        codec.decode<T>(delivery, type)
      } catch (failure: Exception) {
        failure.rethrowIfNonRecoverable()
        currentCoroutineContext().ensureActive()
        onDecodeFailure.handle(spec, delivery, failure)
        return@transform
      }
    // Emitting invokes downstream processing; it must remain outside the decode catch.
    emit(BrokerDelivery(body, delivery))
  }

// Codecs may wrap model-construction failures; cancellation and fatal errors are
// never poison messages. Guard against cyclic cause chains in third-party codecs.
private fun Exception.rethrowIfNonRecoverable() {
  val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
  var failure: Throwable? = this
  while (failure != null && visited.add(failure)) {
    when (failure) {
      is CancellationException, is Error -> throw failure
    }
    failure = failure.cause
  }
}
