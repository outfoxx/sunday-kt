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

package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.isRequestCancelled
import kotlinx.coroutines.jdk9.collect
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger

class HttpRequestsTest {
  @Test
  fun `body subscription guard preserves native settings and leaves the original reusable`() =
    runTest {
      for (length in listOf(-1L, 0L, 7L)) {
        val subscriptions = AtomicInteger()
        val payload = if (length == 0L) byteArrayOf() else "payload".toByteArray()
        val source = HttpRequest.BodyPublishers.ofByteArray(payload)
        val publisher =
          object : HttpRequest.BodyPublisher by source {
            override fun contentLength() = length

            override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
              subscriptions.incrementAndGet()
              source.subscribe(subscriber)
            }
          }
        val original =
          HttpRequest
            .newBuilder(URI("http://example.test/upload"))
            .POST(publisher)
            .header("X-Test", "value")
            .timeout(Duration.ofSeconds(17))
            .version(HttpClient.Version.HTTP_1_1)
            .expectContinue(true)
            .build()
        assertSame(original, original.withBodyRepeatability(true))
        val guarded = original.withBodyRepeatability(false)
        assertEquals(original.uri(), guarded.uri())
        assertEquals(original.method(), guarded.method())
        assertEquals(original.headers(), guarded.headers())
        assertEquals(original.timeout(), guarded.timeout())
        assertEquals(original.version(), guarded.version())
        assertEquals(original.expectContinue(), guarded.expectContinue())
        assertEquals(length, guarded.bodyPublisher().get().contentLength())
        var bytes = 0
        guarded.bodyPublisher().get().collect { bytes += it.remaining() }
        assertEquals(payload.size, bytes)
        val failure = runCatching { guarded.bodyPublisher().get().collect {} }.exceptionOrNull()
        assertTrue(failure?.isRequestCancelled == true)
        assertEquals(1, subscriptions.get())
        original.bodyPublisher().get().collect {}
        assertEquals(2, subscriptions.get())
      }
    }
}
