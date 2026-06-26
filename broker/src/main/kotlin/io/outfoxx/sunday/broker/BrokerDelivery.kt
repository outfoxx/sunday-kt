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

package io.outfoxx.sunday.broker

/**
 * A raw broker delivery plus explicit acknowledgment control.
 */
interface BrokerRawDelivery {

  /** Message carried by the delivery. */
  val message: BrokerMessage

  /** Source exchange, when the transport exposes one. */
  val exchange: String?

  /** Source routing key, when the transport exposes one. */
  val routingKey: String?

  /** Whether the transport reports this delivery as redelivered. */
  val redelivered: Boolean

  /** Acknowledge successful processing. */
  suspend fun ack()

  /** Negatively acknowledge processing failure. */
  suspend fun nack(requeue: Boolean)
}

/**
 * A decoded broker delivery that preserves the raw acknowledgment handle.
 */
class BrokerDelivery<T : Any>(
  val body: T,
  val raw: BrokerRawDelivery,
) {

  /** Acknowledge successful processing. */
  suspend fun ack() = raw.ack()

  /** Negatively acknowledge processing failure. */
  suspend fun nack(requeue: Boolean) = raw.nack(requeue)
}
