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

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class BrokerMessageCodecTest {

  @Test
  fun `round trips JSON payloads`() {
    val codec = BrokerMessageCodec()
    val message = codec.encode(TestMessage("one", 42), contentType = "application/json", routingKey = "test.one")

    expectThat(message.contentType).isEqualTo("application/json")
    expectThat(message.routingKey).isEqualTo("test.one")

    val decoded = codec.decode<TestMessage>(TestDelivery(message))

    expectThat(decoded).isEqualTo(TestMessage("one", 42))
  }

  @Test
  fun `delegates ack and nack from decoded delivery`() =
    runTest {
      val raw = TestDelivery(BrokerMessage(ByteArray(0)))
      val delivery = BrokerDelivery(TestMessage("one", 42), raw)

      delivery.ack()
      delivery.nack(requeue = true)

      expectThat(raw.actions.toList()).isEqualTo(listOf("ack", "nack:true"))
    }

  @Test
  fun `broker specs preserve AMQP metadata`() {
    val spec =
      BrokerConsumeSpec(
        id = "consumePlatformEvents",
        channel = "platform.events",
        contentType = "application/json",
        protocol =
          AmqpBrokerProtocolSpec(
            exchange = "platform.events",
            exchangeType = "topic",
            queue = "platform-events",
            routingKeys = listOf("#"),
            deadLetterExchange = "platform.events.dlx",
          ),
      )

    val amqp = spec.protocol as AmqpBrokerProtocolSpec

    expectThat(spec.channel).isEqualTo("platform.events")
    expectThat(amqp.exchange).isEqualTo("platform.events")
    expectThat(amqp.routingKeys).isEqualTo(listOf("#"))
    expectThat(amqp.deadLetterExchange).isEqualTo("platform.events.dlx")
  }

  data class TestMessage(
    val id: String,
    val count: Int,
  )

  private class TestDelivery(
    override val message: BrokerMessage,
  ) : BrokerRawDelivery {

    val actions = mutableListOf<String>()

    override val exchange: String = "test.exchange"
    override val routingKey: String = message.routingKey ?: "test.key"
    override val redelivered: Boolean = false

    override suspend fun ack() {
      actions += "ack"
    }

    override suspend fun nack(requeue: Boolean) {
      actions += "nack:$requeue"
    }
  }
}
