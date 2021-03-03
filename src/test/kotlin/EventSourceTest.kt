import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.outfoxx.sunday.EventSource
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.http.HeaderNames.ContentType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class EventSourceTest {

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
          200
        )
        .setBodyDelay(1, SECONDS)
    )
    server.start()

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

    val latch = CountDownLatch(1)
    eventSource.onmessage = { _, id, event, data ->

      assertEquals(id, "123")
      assertEquals(event, "test")
      assertEquals(data, "some test data")

      latch.countDown()
    }

    eventSource.connect()

    assertTrue(latch.await(10, SECONDS))
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
          200
        )
        .setBodyDelay(1, SECONDS)
    )
    server.start()

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

    val latch = CountDownLatch(1)
    eventSource.onmessage = { _, id, event, data ->

      val json =
        try {
          ObjectMapper().readValue<Map<String, String>>(data ?: "{}")
        } catch (t: Throwable) {
          fail(t)
        }

      assertEquals(id, "123")
      assertEquals(event, "test")
      assertEquals(json, mapOf("some" to "test data"))

      latch.countDown()
    }

    eventSource.connect()

    assertTrue(latch.await(10, SECONDS))
  }

}
