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
 * Protocol-specific metadata for a generated broker operation.
 */
interface BrokerProtocolSpec {

  /** Source protocol name, such as `amqp`. */
  val protocol: String
}

/**
 * AMQP/RabbitMQ metadata for a generated broker operation.
 */
data class AmqpBrokerProtocolSpec(
  val exchange: String? = null,
  val exchangeType: String? = null,
  val queue: String? = null,
  val routingKeys: List<String> = emptyList(),
  val defaultRoutingKey: String? = null,
  val durable: Boolean? = null,
  val autoDelete: Boolean? = null,
  val deadLetterExchange: String? = null,
  val deadLetterRoutingKey: String? = null,
) : BrokerProtocolSpec {

  override val protocol: String = "amqp"
}
