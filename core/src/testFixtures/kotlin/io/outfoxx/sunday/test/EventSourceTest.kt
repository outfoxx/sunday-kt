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

package io.outfoxx.sunday.test

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.outfoxx.sunday.EventSource
import io.outfoxx.sunday.EventSourceError
import io.outfoxx.sunday.EventSourceError.Reason.EventTimeout
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.MediaType.Companion.Problem
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.HeaderNames.LastEventId
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import strikt.api.expectThat
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isTrue
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

@Timeout(30)
abstract class EventSourceTest {

  abstract fun createRequest(
    url: String,
    headers: Headers,
    onStart: () -> Unit = {},
    onCancel: () -> Unit = {},
  ): Request

  @Test
  fun `test ignore double connect`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |data: some test data
          |
          |
          """.trimMargin(),
          5,
        ).setBodyDelay(500, MILLISECONDS),
    )
    server.start()
    server.use {
      val eventSource =
        EventSource({ headers -> createRequest(server.url("/test").toString(), headers) })

      val completed = CountDownLatch(1)

      eventSource.onMessage = { _ ->
        try {
          eventSource.close()
        } finally {
          completed.countDown()
        }
      }

      eventSource.use {
        eventSource.connect()
        eventSource.connect()

        expectThat(completed.await(3, SECONDS)).isTrue()
      }
    }
  }

  @Test
  fun `test simple data`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |event: test
          |id: 123
          |data: some test data
          |
          |
          """.trimMargin(),
          5,
        ),
    )
    server.start()
    server.use {
      val eventSource =
        EventSource({ headers -> createRequest(server.url("/test").toString(), headers) })

      var receivedEvent: EventSource.Event? = null
      val completed = CountDownLatch(1)
      eventSource.onMessage = {
        receivedEvent = it
        completed.countDown()
      }

      eventSource.use {
        eventSource.connect()

        expectThat(completed.await(300, SECONDS)).isTrue()
        expectThat(receivedEvent)
          .isNotNull()
          .and {
            get { id }.isEqualTo("123")
            get { this.event }.isEqualTo("test")
            get { origin }.isEqualTo(server.url("/test").toString())
            get { data }.isEqualTo("some test data")
          }
      }
    }
  }

  @Test
  fun `test JSON data`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |event: test
          |id: 123
          |data: {"some":
          |data: "test data"}
          |
          |
          """.trimMargin(),
          5,
        ),
    )
    server.start()
    server.use {
      val eventSource =
        EventSource({ headers -> createRequest(server.url("/test").toString(), headers) })

      var receivedEvent: EventSource.Event? = null
      val completed = CountDownLatch(1)
      eventSource.onMessage = {
        receivedEvent = it
        completed.countDown()
      }

      eventSource.use {
        eventSource.connect()

        expectThat(completed.await(3, SECONDS)).isTrue()

        expectThat(receivedEvent)
          .isNotNull()
          .and {
            get { id }.isEqualTo("123")
            get { this.event }.isEqualTo("test")
            get { origin }.isEqualTo(server.url("/test").toString())
          }

        val json = ObjectMapper().readValue<Map<String, String>>(receivedEvent?.data ?: "{}")
        expectThat(json).isEqualTo(mapOf("some" to "test data"))
      }
    }
  }

  @Test
  fun `test callbacks`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setBody(
          """
          |data: some test data
          |
          |
          """.trimMargin(),
        ),
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(400)
        .setHeader(ContentType, Problem),
    )
    server.start()
    server.use {
      val eventSource =
        EventSource(
          { headers -> createRequest(server.url("/test").toString(), headers) },
          retryTime = Duration.ofMillis(100),
        )

      val opened = CountDownLatch(1)
      val messaged = CountDownLatch(1)
      val errored = CountDownLatch(1)

      eventSource.onOpen = {
        opened.countDown()
      }
      expectThat(eventSource.onOpen).isNotNull()

      eventSource.onMessage = { _ ->
        messaged.countDown()
      }
      expectThat(eventSource.onMessage).isNotNull()

      eventSource.onError = { _ ->
        errored.countDown()
      }
      expectThat(eventSource.onError).isNotNull()

      eventSource.use {
        eventSource.connect()

        expectThat(opened.await(3, SECONDS)).isTrue()
        expectThat(messaged.await(3, SECONDS)).isTrue()
        expectThat(errored.await(3, SECONDS)).isTrue()
      }
    }
  }

  @Test
  fun `test listener add & remove`() {
    val eventSource = EventSource(
      { headers -> createRequest("http://example.com", headers) },
    )

    val handler: (EventSource.Event) -> Unit = { }
    eventSource.addEventListener("test", handler)
    eventSource.removeEventListener("test", handler)

    expectThat(eventSource.eventListeners.keys).isEmpty()
  }

  @Test
  fun `valid retry timeout update`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |retry: 123456789
          |data: some test data
          |
          |
          """.trimMargin(),
          5,
        ),
    )
    server.start()
    server.use {
      val eventSource =
        EventSource({ headers -> createRequest(server.url("/test").toString(), headers) })

      val completed = CountDownLatch(1)

      eventSource.onMessage = { _ ->
        completed.countDown()
      }

      eventSource.use {
        eventSource.connect()

        expectThat(completed.await(3, SECONDS)).isTrue()
        expectThat(eventSource.retryTime).isEqualTo(Duration.ofMillis(123456789L))
      }
    }
  }

  @Test
  fun `invalid retry timeout updates are ignored`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |retry: long
          |data: some test data
          |
          |
          """.trimMargin(),
          5,
        ),
    )
    server.start()
    server.use {
      val eventSource =
        EventSource({ headers -> createRequest(server.url("/test").toString(), headers) })

      val completed = CountDownLatch(1)

      eventSource.onMessage = { _ ->
        completed.countDown()
      }

      eventSource.use {
        eventSource.connect()

        expectThat(completed.await(3, SECONDS)).isTrue()
        expectThat(eventSource.retryTime).isEqualTo(Duration.ofMillis(500L))
      }
    }
  }

  @Test
  fun `reconnect with last-event-id`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |id: 123-abc
          |data: some test data
          |
          |
          """.trimMargin(),
          5,
        ),
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(503),
    )
    server.start()
    server.use {
      val eventSource =
        EventSource({ headers -> createRequest(server.url("/test").toString(), headers) })

      eventSource.use {
        eventSource.connect()

        server.takeRequest(3, SECONDS)
        val reconnectRequest = server.takeRequest(3, SECONDS)

        expectThat(reconnectRequest)
          .isNotNull()
          .and { get { getHeader(LastEventId) }.isEqualTo("123-abc") }
      }
    }
  }

  @Test
  fun `reconnect with last-event-id ignores invalid ids`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |id: 123-abc
          |data: some test data
          |
          |
          """.trimMargin(),
          5,
        ),
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |id: 456${0.toChar()}def
          |data: some test data
          |
          |
          """.trimMargin(),
          5,
        ),
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(503),
    )
    server.start()
    server.use {
      val eventSource =
        EventSource(
          { headers -> createRequest(server.url("/test").toString(), headers) },
          retryTime = Duration.ofMillis(100),
        )

      eventSource.use {
        eventSource.connect()

        server.takeRequest(3, SECONDS)
        val reconnect1 = server.takeRequest(3, SECONDS)
        val reconnect2 = server.takeRequest(3, SECONDS)

        expectThat(reconnect1)
          .isNotNull()
          .and { get { getHeader(LastEventId) }.isEqualTo("123-abc") }

        expectThat(reconnect2)
          .isNotNull()
          .and { get { getHeader(LastEventId) }.isEqualTo("123-abc") }
      }
    }
  }

  @Test
  fun `event timeout reconnects`() {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |data: some test data
          |
          |
          """.trimMargin(),
          5,
        ).throttleBody(20, 2, SECONDS),
    )
    server.start()
    server.use {
      val eventSource =
        EventSource(
          { headers -> createRequest(server.url("/test").toString(), headers) },
          eventTimeout = Duration.ofMillis(500),
          eventTimeoutCheckInterval = Duration.ofMillis(100),
        )

      val completed = CountDownLatch(1)

      var error: EventSourceError? = null
      eventSource.onError = {
        if (it is EventSourceError) {
          error = it
          completed.countDown()
        }
      }

      eventSource.use {
        eventSource.connect()

        expectThat(completed.await(12, SECONDS)).isTrue()
        expectThat(error)
          .isNotNull()
          .and { get { reason }.isEqualTo(EventTimeout) }
      }
    }
  }

  @Test
  fun `cancellation closes connection`() {
    val canceled = CountDownLatch(1)

    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .addHeader(ContentType, EventStream)
        .setChunkedBody(
          """
          |data: ${"x".repeat(100000)}
          |
          |
          |data: ${"x".repeat(100000)}
          |
          |
          |data: ${"x".repeat(100000)}
          |
          |
          |data: ${"x".repeat(100000)}
          |
          |
          """.trimMargin(),
          3,
        ),
    )
    server.start()
    server.use {
      val connected = CountDownLatch(1)

      val eventSource =
        EventSource(
          { headers ->
            createRequest(server.url("/test").toString(), headers, connected::countDown) {
              println("### CANCELED")
              canceled.countDown()
            }
          },
        )

      eventSource.connect()

      expectThat(connected.await(12, SECONDS)).isTrue()

      eventSource.close()

      expectThat(canceled.await(12, SECONDS)).isTrue()
    }
  }

}
