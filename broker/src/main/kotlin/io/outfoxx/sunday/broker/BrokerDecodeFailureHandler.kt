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

/**
 * Handles an undecodable delivery without replacing its bytes or settlement handle.
 *
 * Returning indicates that the failure was handled and consumption may continue.
 * The handler owns settlement: acknowledge only after any required durable transfer
 * succeeds. Throwing aborts collection and leaves transport recovery to the caller.
 */
fun interface BrokerDecodeFailureHandler {

  /** Handle a decoding [failure] for [delivery] consumed using [spec]. */
  suspend fun handle(
    spec: BrokerConsumeSpec,
    delivery: BrokerRawDelivery,
    failure: Exception,
  )

  companion object {

    /** Preserves the fail-fast behavior of consumers without a recovery policy. */
    val Rethrow = BrokerDecodeFailureHandler { _, _, failure -> throw failure }
  }
}
