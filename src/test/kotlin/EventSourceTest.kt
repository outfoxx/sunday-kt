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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.outfoxx.sunday.EventSource
import io.outfoxx.sunday.EventSourceError
import io.outfoxx.sunday.EventSourceError.Reason.EventTimeout
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.http.HeaderNames.ContentType
import io.outfoxx.sunday.http.HeaderNames.LastEventId
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

@Timeout(30)
class EventSourceTest {

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
          5
        )
        .setBodyDelay(500, MILLISECONDS)
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val eventSource =
        EventSource({ headers ->
                      httpClient.newCall(
                        Request.Builder()
                          .url(server.url("/test"))
                          .headers(headers)
                          .build()
                      )
                    })

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

        assertTrue(completed.await(3, SECONDS))
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
          5
        )
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val eventSource =
        EventSource({ headers ->
                      httpClient.newCall(
                        Request.Builder()
                          .url(server.url("/test"))
                          .headers(headers)
                          .build()
                      )
                    })

      var event: EventSource.Event? = null
      val completed = CountDownLatch(1)
      eventSource.onMessage = {
        event = it
        completed.countDown()
      }

      eventSource.use {

        eventSource.connect()

        assertTrue(completed.await(300, SECONDS))
        assertThat(event, not(nullValue()))
        assertThat(event?.id, equalTo("123"))
        assertThat(event?.event, equalTo("test"))
        assertThat(event?.origin, equalTo(server.url("/test").toString()))
        assertThat(event?.data, equalTo("some test data"))
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
          5
        )
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val eventSource =
        EventSource({ headers ->
                      httpClient.newCall(
                        Request.Builder()
                          .url(server.url("/test"))
                          .headers(headers)
                          .build()
                      )
                    })

      var event: EventSource.Event? = null
      val completed = CountDownLatch(1)
      eventSource.onMessage = {
        event = it
        completed.countDown()
      }

      eventSource.use {

        eventSource.connect()

        assertTrue(completed.await(3, SECONDS))

        assertThat(event, not(nullValue()))
        assertThat(event?.id, equalTo("123"))
        assertThat(event?.event, equalTo("test"))
        assertThat(event?.origin, equalTo(server.url("/test").toString()))

        val json =
          try {
            ObjectMapper().readValue<Map<String, String>>(event?.data ?: "{}")
          } catch (t: Throwable) {
            fail("Event contains invalid JSON", t)
          }
        assertThat(json, equalTo(mapOf("some" to "test data")))
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
        )
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(400)
        .setHeader(ContentType, EventStream)
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val eventSource =
        EventSource(
          { headers ->
            httpClient.newCall(
              Request.Builder()
                .url(server.url("/test"))
                .headers(headers)
                .build()
            )
          },
          retryTime = Duration.ofMillis(100)
        )

      val opened = CountDownLatch(1)
      val messaged = CountDownLatch(1)
      val errored = CountDownLatch(1)

      eventSource.onOpen = {
        opened.countDown()
      }
      assertThat(eventSource.onOpen, not(nullValue()))

      eventSource.onMessage = { _ ->
        messaged.countDown()
      }
      assertThat(eventSource.onMessage, not(nullValue()))

      eventSource.onError = { _ ->
        errored.countDown()
      }
      assertThat(eventSource.onError, not(nullValue()))

      eventSource.use {

        eventSource.connect()

        assertTrue(opened.await(3, SECONDS), "Did not received open callback")
        assertTrue(messaged.await(3, SECONDS), "Did not received message callback")
        assertTrue(errored.await(3, SECONDS), "Did not received error callback")
      }
    }
  }

  @Test
  fun `test listener add & remove`() {

    val httpClient = OkHttpClient.Builder().build()

    val eventSource =
      EventSource({ headers ->
                    httpClient.newCall(
                      Request.Builder()
                        .url("http://example.com")
                        .headers(headers)
                        .build()
                    )
                  })

    eventSource.addEventListener("test") { }
    eventSource.removeEventListener("test")

    assertThat(eventSource.eventListeners.keys, empty())
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
          5
        )
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val eventSource =
        EventSource({ headers ->
                      httpClient.newCall(
                        Request.Builder()
                          .url(server.url("/test"))
                          .headers(headers)
                          .build()
                      )
                    })

      val completed = CountDownLatch(1)

      eventSource.onMessage = { _ ->
        completed.countDown()
      }

      eventSource.use {

        eventSource.connect()

        assertTrue(completed.await(3, SECONDS))
        assertThat(eventSource.retryTime, equalTo(Duration.ofMillis(123456789L)))
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
          5
        )
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val eventSource =
        EventSource({ headers ->
                      httpClient.newCall(
                        Request.Builder()
                          .url(server.url("/test"))
                          .headers(headers)
                          .build()
                      )
                    })

      val completed = CountDownLatch(1)

      eventSource.onMessage = { _ ->
        completed.countDown()
      }

      eventSource.use {

        eventSource.connect()

        assertTrue(completed.await(3, SECONDS))
        assertThat(eventSource.retryTime, equalTo(Duration.ofMillis(500L)))
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
          5
        )
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(503)
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val eventSource =
        EventSource({ headers ->
                      httpClient.newCall(
                        Request.Builder()
                          .url(server.url("/test"))
                          .headers(headers)
                          .build()
                      )
                    })

      eventSource.use {

        eventSource.connect()

        server.takeRequest(3, SECONDS)
        val reconnectRequest = server.takeRequest(3, SECONDS)

        assertThat(reconnectRequest, not(nullValue()))
        assertThat(reconnectRequest?.getHeader(LastEventId), equalTo("123-abc"))
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
          5
        )
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
          5
        )
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(503)
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val eventSource =
        EventSource(
          { headers ->
                      httpClient.newCall(
                        Request.Builder()
                          .url(server.url("/test"))
                          .headers(headers)
                          .build()
                      )
                    },
          retryTime = Duration.ofMillis(100)
        )

      eventSource.use {

        eventSource.connect()

        server.takeRequest(3, SECONDS)
        val reconnect1 = server.takeRequest(3, SECONDS)
        val reconnect2 = server.takeRequest(3, SECONDS)

        assertThat(reconnect1, not(nullValue()))
        assertThat(reconnect1?.getHeader(LastEventId), equalTo("123-abc"))

        assertThat(reconnect2, not(nullValue()))
        assertThat(reconnect2?.getHeader(LastEventId), equalTo("123-abc"))
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
          5
        )
    )
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("retry: 500\n\n")
        .setBodyDelay(2, SECONDS)
    )
    server.start()
    server.use {

      val httpClient = OkHttpClient.Builder().build()

      val eventSource =
        EventSource(
          { headers ->
            httpClient.newCall(
              Request.Builder()
                .url(server.url("/test"))
                .headers(headers)
                .build()
            )
          },
          eventTimeout = Duration.ofMillis(500),
          eventTimeoutCheckInterval = Duration.ofMillis(100)
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

        assertThat(completed.await(2, SECONDS), equalTo(true))
        assertThat(error?.reason, equalTo(EventTimeout))
      }
    }
  }

}
