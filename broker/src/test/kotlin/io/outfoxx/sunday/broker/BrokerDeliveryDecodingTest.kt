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

import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoder
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isSameInstanceAs
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class BrokerDeliveryDecodingTest {

  private val spec =
    BrokerConsumeSpec("consumeEvents", "events", protocol = AmqpBrokerProtocolSpec(exchange = "events"))
  private val codec = BrokerMessageCodec()

  @ParameterizedTest
  @ValueSource(strings = ["not json", "null", "{}"])
  fun `handles malformed delivery then continues with valid delivery`(body: String) =
    runTest {
      val malformed = Delivery(BrokerMessage(body.encodeToByteArray(), "application/json"))
      val valid = Delivery(codec.encode(Payload("event-1"), "application/json"))
      val handled = mutableListOf<BrokerRawDelivery>()
      val handler =
        BrokerDecodeFailureHandler { actualSpec, raw, _ ->
          expectThat(actualSpec).isSameInstanceAs(spec)
          handled += raw
          raw.ack()
        }

      val values = flowOf(malformed, valid).decodeDeliveries<Payload>(spec, typeOf<Payload>(), codec, handler).toList()

      expectThat(handled.toList()).isEqualTo(listOf(malformed))
      expectThat(values.map { it.body }).isEqualTo(listOf(Payload("event-1")))
      expectThat(values.single().raw).isSameInstanceAs(valid)
      expectThat(malformed.actions.toList()).isEqualTo(listOf("ack"))
      expectThat(valid.actions).isEmpty()
    }

  @Test
  fun `handles missing decoder then continues with valid delivery without automatic settlement`() =
    runTest {
      val unsupported =
        Delivery(BrokerMessage("""{"id":"event-unsupported"}""".encodeToByteArray(), "application/x-unregistered"))
      val valid = Delivery(codec.encode(Payload("event-1"), "application/json"))
      val failures = mutableListOf<Exception>()
      val handler =
        BrokerDecodeFailureHandler { actualSpec, raw, failure ->
          expectThat(actualSpec).isSameInstanceAs(spec)
          expectThat(raw).isSameInstanceAs(unsupported)
          failures += failure
        }

      val values =
        flowOf(unsupported, valid).decodeDeliveries<Payload>(spec, typeOf<Payload>(), codec, handler).toList()

      expectThat(failures.single()).isA<BrokerCodecException>().and {
        get { message }.isEqualTo("No broker decoder registered for media type 'application/x-unregistered'")
      }
      expectThat(values.map { it.body }).isEqualTo(listOf(Payload("event-1")))
      expectThat(values.single().raw).isSameInstanceAs(valid)
      expectThat(unsupported.actions).isEmpty()
      expectThat(valid.actions).isEmpty()
    }

  @Test
  fun `default policy rethrows and does not settle the delivery`() =
    runTest {
      val raw = Delivery(BrokerMessage("not json".encodeToByteArray(), "application/json"))

      expectThrows<Exception> {
        flowOf(raw).decodeDeliveries<Payload>(spec, typeOf<Payload>()).toList()
      }

      expectThat(raw.actions).isEmpty()
    }

  @Test
  fun `downstream early completion does not invoke recovery`() =
    runTest {
      val raw = Delivery(codec.encode(Payload("event-1"), "application/json"))
      val handled = mutableListOf<Exception>()
      val handler = BrokerDecodeFailureHandler { _, _, failure -> handled += failure }

      val values =
        flowOf(raw, raw).decodeDeliveries<Payload>(spec, typeOf<Payload>(), codec, handler).take(1).toList()

      expectThat(values.map { it.body }).isEqualTo(listOf(Payload("event-1")))
      expectThat(handled).isEmpty()
      expectThat(raw.actions).isEmpty()
    }

  @Test
  fun `waits for recovery to finish without automatically settling the delivery`() =
    runTest {
      val malformed = Delivery(BrokerMessage("not json".encodeToByteArray(), "application/json"))
      val valid = Delivery(codec.encode(Payload("event-1"), "application/json"))
      val recoveryStarted = CompletableDeferred<Unit>()
      val recoveryFinished = CompletableDeferred<Unit>()
      val handler =
        BrokerDecodeFailureHandler { _, _, _ ->
          recoveryStarted.complete(Unit)
          recoveryFinished.await()
        }
      val collection =
        async(start = CoroutineStart.UNDISPATCHED) {
          flowOf(malformed, valid).decodeDeliveries<Payload>(spec, typeOf<Payload>(), codec, handler).toList()
        }

      recoveryStarted.await()
      expectThat(collection.isCompleted).isFalse()
      expectThat(malformed.actions).isEmpty()
      recoveryFinished.complete(Unit)

      expectThat(collection.await().map { it.body }).isEqualTo(listOf(Payload("event-1")))
      expectThat(malformed.actions).isEmpty()
      expectThat(valid.actions).isEmpty()
    }

  @Test
  fun `does not classify downstream or transport errors as decoding failures`() =
    runTest {
      val failure = IllegalStateException("processing failed")
      val handled = mutableListOf<Exception>()
      val handler = BrokerDecodeFailureHandler { _, _, error -> handled += error }
      val raw = Delivery(codec.encode(Payload("event-1"), "application/json"))

      expectThrows<IllegalStateException> {
        flowOf(raw).decodeDeliveries<Payload>(spec, typeOf<Payload>(), codec, handler).collect { throw failure }
      }.isSameInstanceAs(failure)
      expectThrows<IllegalStateException> {
        flow<BrokerRawDelivery> { throw failure }
          .decodeDeliveries<Payload>(spec, typeOf<Payload>(), codec, handler)
          .toList()
      }.isSameInstanceAs(failure)

      expectThat(handled).isEmpty()
      expectThat(raw.actions).isEmpty()
    }

  @Test
  fun `handler failure stops consumption without automatic settlement`() =
    runTest {
      val malformed = Delivery(BrokerMessage("not json".encodeToByteArray(), "application/json"))
      val valid = Delivery(codec.encode(Payload("event-1"), "application/json"))
      val failure = IllegalStateException("quarantine unavailable")

      expectThrows<IllegalStateException> {
        flowOf(malformed, valid)
          .decodeDeliveries<Payload>(spec, typeOf<Payload>(), codec) { _, _, _ -> throw failure }
          .toList()
      }.isSameInstanceAs(failure)

      expectThat(malformed.actions).isEmpty()
      expectThat(valid.actions).isEmpty()
    }

  @Test
  fun `cancellation and fatal decoding errors bypass recovery`() =
    runTest {
      val handled = mutableListOf<Exception>()
      val handler = BrokerDecodeFailureHandler { _, _, error -> handled += error }
      val raw = Delivery(BrokerMessage(ByteArray(0), "application/json"))

      for (failure in listOf(CancellationException("cancelled"), LinkageError("fatal"))) {
        for (wrapped in listOf(failure, IllegalStateException("codec wrapper", RuntimeException(failure)))) {
          val decoder =
            object : MediaTypeDecoder {
              override fun <T : Any> decode(
                data: Source,
                type: KType,
              ): T = throw wrapped
            }
          val failingCodec = BrokerMessageCodec(decoders = MediaTypeDecoders(mapOf(MediaType.JSON to decoder)))

          expectThrows<Throwable> {
            flowOf(raw).decodeDeliveries<Payload>(spec, typeOf<Payload>(), failingCodec, handler).toList()
          }.isSameInstanceAs(failure)
        }
      }

      expectThat(handled).isEmpty()
      expectThat(raw.actions).isEmpty()
    }

  @Test
  fun `ordinary failures preserve their identity even with cyclic causes`() =
    runTest {
      val raw = Delivery(BrokerMessage(ByteArray(0), "application/json"))
      val failure = IllegalArgumentException("invalid payload")
      val wrapper = IllegalStateException("codec wrapper", failure)
      failure.initCause(wrapper)
      val decoder =
        object : MediaTypeDecoder {
          override fun <T : Any> decode(
            data: Source,
            type: KType,
          ): T = throw wrapper
        }
      val failingCodec = BrokerMessageCodec(decoders = MediaTypeDecoders(mapOf(MediaType.JSON to decoder)))
      val handled = mutableListOf<Exception>()
      val handler = BrokerDecodeFailureHandler { _, _, error -> handled += error }

      val values = flowOf(raw).decodeDeliveries<Payload>(spec, typeOf<Payload>(), failingCodec, handler).toList()

      expectThat(handled.single()).isSameInstanceAs(wrapper)
      expectThat(values).isEmpty()
      expectThrows<IllegalStateException> {
        flowOf(raw).decodeDeliveries<Payload>(spec, typeOf<Payload>(), failingCodec).toList()
      }.isSameInstanceAs(wrapper)
      expectThat(raw.actions).isEmpty()
    }

  @Test
  fun `cancellation and fatal recovery errors stop consumption without settlement`() =
    runTest {
      val malformed = Delivery(BrokerMessage("not json".encodeToByteArray(), "application/json"))
      val valid = Delivery(codec.encode(Payload("event-1"), "application/json"))

      for (failure in listOf(CancellationException("cancelled"), LinkageError("fatal"))) {
        expectThrows<Throwable> {
          flowOf(malformed, valid)
            .decodeDeliveries<Payload>(spec, typeOf<Payload>(), codec) { _, _, _ -> throw failure }
            .toList()
        }.isSameInstanceAs(failure)
      }

      expectThat(malformed.actions).isEmpty()
      expectThat(valid.actions).isEmpty()
    }

  @Test
  fun `cancellation during failed decoding does not quarantine the delivery`() =
    runTest {
      val raw = Delivery(BrokerMessage(ByteArray(0), "application/json"))
      val handled = mutableListOf<Exception>()
      val handler = BrokerDecodeFailureHandler { _, _, failure -> handled += failure }

      val collection =
        launch {
          val context = currentCoroutineContext()
          val decoder =
            object : MediaTypeDecoder {
              override fun <T : Any> decode(
                data: Source,
                type: KType,
              ): T {
                context.cancel()
                throw IllegalArgumentException("invalid payload")
              }
            }
          val cancellingCodec = BrokerMessageCodec(decoders = MediaTypeDecoders(mapOf(MediaType.JSON to decoder)))

          flowOf(raw).decodeDeliveries<Payload>(spec, typeOf<Payload>(), cancellingCodec, handler).toList()
        }
      collection.join()

      expectThat(handled).isEmpty()
      expectThat(raw.actions).isEmpty()
    }

  @Test
  fun `cancellation from a JSON model constructor bypasses recovery`() =
    runTest {
      val raw = Delivery(BrokerMessage("""{"id":"event-1"}""".encodeToByteArray(), "application/json"))
      val handled = mutableListOf<Exception>()
      val handler = BrokerDecodeFailureHandler { _, _, failure -> handled += failure }

      expectThrows<CancellationException> {
        flowOf(raw).decodeDeliveries<CancelledPayload>(spec, typeOf<CancelledPayload>(), codec, handler).toList()
      }

      expectThat(handled).isEmpty()
      expectThat(raw.actions).isEmpty()
    }

  data class Payload(
    val id: String,
  )

  data class CancelledPayload(
    val id: String,
  ) {
    init {
      throw CancellationException("cancelled model construction")
    }
  }

  private class Delivery(
    override val message: BrokerMessage,
  ) : BrokerRawDelivery {
    override val exchange = "events"
    override val routingKey = "event.created.scope"
    override val redelivered = false
    val actions = mutableListOf<String>()

    override suspend fun ack() {
      actions += "ack"
    }

    override suspend fun nack(requeue: Boolean) {
      actions += "nack:$requeue"
    }
  }
}
