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

import io.outfoxx.sunday.RequestAdapter
import io.outfoxx.sunday.RequestCancellationException
import io.outfoxx.sunday.ResponseAdapter
import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.causes
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.ResponseEvent
import io.outfoxx.sunday.http.copy
import io.outfoxx.sunday.isRequestCancelled
import io.outfoxx.sunday.security.AuthorizationRequiredException
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.TokenConfiguration
import io.outfoxx.sunday.security.TokenProvider
import io.outfoxx.sunday.security.TokenProviderException
import io.outfoxx.sunday.security.TokenRejectedException
import io.outfoxx.sunday.security.TokenRequest
import io.outfoxx.sunday.security.TokenSet
import io.outfoxx.sunday.withSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.URI
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Timeout(30)
abstract class AdapterExecutionTest<TReq, TRes> {
  abstract fun transport(
    base: URITemplate,
    requestAdapter: RequestAdapter? = null,
    responseAdapter: ResponseAdapter? = null,
    providers: Map<String, TokenProvider> = emptyMap(),
  ): Transport<TReq, TRes>

  /** Executes the exported native request using a consumer-owned client, bypassing Sunday policy. */
  abstract suspend fun executeExport(request: Request<TReq>): Int

  /** Application decorator whose own copies retain its replay restriction. */
  protected class RestrictedRequest<TReq>(
    private val original: Request<TReq>,
  ) : Request<TReq> by original {
    override val isBodyRepeatable = false

    override fun copy(
      method: Method,
      uri: URI,
      headers: Headers,
    ): Request<TReq> = RestrictedRequest(original.copy(method, uri, headers))

    override fun copy(transport: TReq): Request<TReq> = RestrictedRequest(original.copy(transport))

    override fun withBody(body: StreamingBody?): Request<TReq> = RestrictedRequest(original.withBody(body))
  }

  @Test
  fun `effective request copies preserve non-repeatability during buffered execution`() =
    checkEffectiveRequestCopies(false)

  @Test
  fun `effective request copies preserve non-repeatability during streaming execution`() =
    checkEffectiveRequestCopies(true)

  private fun checkEffectiveRequestCopies(streaming: Boolean) =
    runTest {
      val observed = AtomicInteger()
      val restrict =
        object : RequestAdapter {
          override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> = RestrictedRequest(request)
        }
      val copy =
        object : ResponseAdapter {
          override suspend fun <TRes> adapt(
            response: Response<TRes>,
            request: Request<*>,
          ): Response<TRes> {
            observed.incrementAndGet()
            return response.copy(request = verifyRestrictedCopies(request))
          }
        }
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("accepted"))
        transport(URITemplate(server.url("/").toString()), restrict, copy).use { transport ->
          val result = perform(transport, streaming, Method.Options)
          assertFalse(result.request.isBodyRepeatable)
          result.body!!.use { assertEquals("accepted", it.readString()) }
          assertEquals(1, observed.get())
          assertEquals(1, server.requestCount)
        }
      }
    }

  private fun <TReq> verifyRestrictedCopies(request: Request<TReq>): Request<TReq> {
    assertFalse(request.isBodyRepeatable)
    val fields = request.copy(headers = request.headers + ("X-Copy" to "yes"))
    assertFalse(fields.isBodyRepeatable, "Copying fields must retain the restriction")
    assertEquals("yes", fields.headers.first { it.first == "X-Copy" }.second)
    val native = fields.copy(transport = fields.transport)
    assertFalse(native.isBodyRepeatable, "Copying the native request must retain the restriction")
    assertSame(fields.transport, native.transport)
    val replaced = native.withBody(StreamingBody.source { Buffer().apply { writeString("replacement") } })
    assertFalse(replaced.isBodyRepeatable, "Replacing the body must not silently relax an explicit restriction")
    return native
  }

  private class ReadStop :
    IOException("stop reading"),
    RequestCancellationException

  private class Stop :
    RuntimeException("stop"),
    RequestCancellationException

  protected suspend fun perform(
    transport: Transport<TReq, TRes>,
    streaming: Boolean,
    method: Method = Method.Get,
  ): Response<*> {
    val request = transport.transportRequest<Unit>(method, "value", purpose = Transport.RequestPurpose.Normal)
    if (!streaming) return transport.transportResponse(request)
    val events = transport.start(request).toList()
    val body = Buffer()
    events.filterIsInstance<ResponseEvent.Data>().forEach { body.write(it.value.readByteArray()) }
    return events
      .filterIsInstance<ResponseEvent.Start<*>>()
      .single()
      .value
      .copy(body = body)
  }

  @Test
  fun `request and response chains run once in explicit order for both execution modes`() =
    runTest {
      for (streaming in listOf(false, true)) {
        val calls = mutableListOf<String>()
        val request =
          object : RequestAdapter {
            override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
              calls += "request-1"
              return request.copy(headers = request.headers + ("X-Adapted" to "yes"))
            }
          }.chainTo(
            object : RequestAdapter {
              override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
                calls += "request-2"
                return request
              }
            },
          )
        val response =
          object : ResponseAdapter {
            override suspend fun <TRes> adapt(
              response: Response<TRes>,
              request: Request<*>,
            ): Response<TRes> {
              assertTrue(request.headers.contains("X-Adapted" to "yes"))
              calls += "response-1"
              return response.copy(statusCode = 200)
            }
          }.chainTo(
            object : ResponseAdapter {
              override suspend fun <TRes> adapt(
                response: Response<TRes>,
                request: Request<*>,
              ): Response<TRes> {
                calls += "response-2"
                return response
              }
            },
          )
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setResponseCode(503).setBody("body"))
          transport(URITemplate(server.url("/").toString()), request, response).use { transport ->
            val result = perform(transport, streaming)
            assertEquals(200, result.statusCode)
            assertEquals("body", result.body!!.readString())
            result.body?.close()
            assertEquals(listOf("request-1", "request-2", "response-1", "response-2"), calls)
            assertEquals("yes", server.takeRequest().getHeader("X-Adapted"))
          }
        }
      }
    }

  @Test
  fun `response capability is never inferred and exports adapt only once`() =
    runTest {
      val requests = AtomicInteger()
      val responses = AtomicInteger()
      val dual =
        object : RequestAdapter, ResponseAdapter {
          override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
            requests.incrementAndGet()
            return request
          }

          override suspend fun <TRes> adapt(
            response: Response<TRes>,
            request: Request<*>,
          ): Response<TRes> {
            responses.incrementAndGet()
            return response
          }
        }
      MockWebServer().use { server ->
        repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
        transport(URITemplate(server.url("/").toString()), dual).use { transport ->
          perform(transport, false).body?.close()
          val exported = transport.transportRequest(Method.Get, "value")
          assertEquals(204, executeExport(exported))
          assertEquals(2, requests.get())
          assertEquals(0, responses.get())
        }
      }
    }

  @Test
  fun `adapter stop errors preserve identity without sends retries or later adapters`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (inbound in listOf(false, true)) {
          val stop = Stop()
          MockWebServer().use { server ->
            if (inbound) server.enqueue(MockResponse().setBody("discard"))
            val request =
              if (inbound) {
                null
              } else {
                object : RequestAdapter {
                  override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> = throw stop
                }.chainTo(
                  object : RequestAdapter {
                    override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> =
                      fail("Later request adapter ran")
                  },
                )
              }
            val response =
              if (!inbound) {
                null
              } else {
                object : ResponseAdapter {
                  override suspend fun <TRes> adapt(
                    response: Response<TRes>,
                    request: Request<*>,
                  ): Response<TRes> = throw stop
                }.chainTo(
                  object : ResponseAdapter {
                    override suspend fun <TRes> adapt(
                      response: Response<TRes>,
                      request: Request<*>,
                    ): Response<TRes> = fail("Later response adapter ran")
                  },
                )
              }
            transport(URITemplate(server.url("/").toString()), request, response).use { transport ->
              assertSame(stop, runCatching { perform(transport, streaming) }.exceptionOrNull())
              assertEquals(if (inbound) 1 else 0, server.requestCount)
            }
          }
        }
      }
    }

  @Test
  fun `replacement upload factory failures preserve original exceptions through native callbacks`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (failOn in listOf(1, 2)) {
          val stop = Stop()
          val opens = AtomicInteger()
          val request =
            object : RequestAdapter {
              override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> =
                request.withBody(
                  StreamingBody.source {
                    if (opens.incrementAndGet() == failOn) throw stop
                    Buffer().apply { writeString("replacement") }
                  },
                )
            }
          MockWebServer().use { server ->
            if (failOn == 2) server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/replay"))
            server.enqueue(MockResponse())
            transport(URITemplate(server.url("/").toString()), request).use { transport ->
              assertTrue(
                runCatching { perform(transport, streaming, Method.Post) }.exceptionOrNull()!!.causes().any {
                  it ===
                    stop
                },
              )
              assertEquals(failOn, opens.get())
              if (failOn == 2) assertEquals("replacement", server.takeRequest().body.readUtf8())
            }
          }
        }
      }
    }

  @Test
  fun `response adapters may consume and replace the body in either mode`() =
    runTest {
      for (streaming in listOf(false, true)) {
        val response =
          object : ResponseAdapter {
            override suspend fun <TRes> adapt(
              response: Response<TRes>,
              request: Request<*>,
            ): Response<TRes> {
              response.body!!.use { assertEquals("original", it.readString()) }
              return response.copy(body = Buffer().apply { writeString("replacement") })
            }
          }
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setBody("original"))
          transport(URITemplate(server.url("/").toString()), responseAdapter = response).use { transport ->
            val result = perform(transport, streaming)
            result.body!!.use { assertEquals("replacement", it.readString()) }
          }
        }
      }
    }

  @Test
  fun `empty-path redirects preserve the path during buffered execution`() = checkEmptyPathRedirects(false)

  @Test
  fun `empty-path redirects preserve the path during streaming execution`() = checkEmptyPathRedirects(true)

  private fun checkEmptyPathRedirects(streaming: Boolean) =
    runTest {
      val cases =
        listOf(
          Triple("/items/list?page=1", "", "/items/list?page=1"),
          Triple("/items/list", "", "/items/list"),
          Triple("/items/list?page=1", "#section", "/items/list?page=1"),
          Triple("/items/a%2Fb%3Fc%23d%25?page=%2F%3F%23%25", "", "/items/a%2Fb%3Fc%23d%25?page=%2F%3F%23%25"),
          Triple("/items/list?page=1", "?page=2", "/items/list?page=2"),
          Triple("/items/list", "?page=2", "/items/list?page=2"),
          Triple("/items/list?page=1", "?", "/items/list?"),
          Triple("/items/list?page=1", "?page=2#section", "/items/list?page=2"),
          Triple(
            "/items/a%2Fb%3Fc%23d%25?page=1",
            "?page=%2F%3F%23%25",
            "/items/a%2Fb%3Fc%23d%25?page=%2F%3F%23%25",
          ),
          Triple("/items/list?page=1", "next?page=2", "/items/next?page=2"),
        )
      for ((path, location, expected) in cases) {
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
          server.enqueue(MockResponse().setBody("final"))
          val requests = AtomicInteger()
          transport(
            URITemplate(server.url("/").toString()),
            requestAdapter =
              object : RequestAdapter {
                override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> =
                  if (requests.getAndIncrement() == 0) {
                    request.copy(uri = server.url(path).toUri())
                  } else {
                    request
                  }
              },
          ).use { transport ->
            val result = perform(transport, streaming)
            assertEquals(200, result.statusCode)
            result.body!!.use { assertEquals("final", it.readString()) }
            assertEquals(2, server.requestCount)
            assertEquals(path, server.takeRequest().path)
            // The JDK client omits a bare query delimiter on the wire.
            assertEquals(expected.removeSuffix("?"), server.takeRequest().path?.removeSuffix("?"), "$path -> $location")
          }
        }
      }
    }

  @Test
  fun `adapted redirects run hooks for every attempt and apply see-other semantics`() =
    runTest {
      for (streaming in listOf(false, true)) {
        val requests = AtomicInteger()
        val responses = AtomicInteger()
        val request =
          object : RequestAdapter {
            override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
              requests.incrementAndGet()
              return request
            }
          }
        val response =
          object : ResponseAdapter {
            override suspend fun <TRes> adapt(
              response: Response<TRes>,
              request: Request<*>,
            ): Response<TRes> =
              if (responses.incrementAndGet() == 1) {
                response.copy(statusCode = 303, headers = listOf("Location" to "/end"))
              } else {
                response
              }
          }
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setBody("discarded"))
          server.enqueue(MockResponse().setBody("final"))
          transport(URITemplate(server.url("/").toString()), request, response).use { transport ->
            val result = perform(transport, streaming, Method.Post)
            result.body!!.use { assertEquals("final", it.readString()) }
            assertEquals("POST", server.takeRequest().method)
            val redirected = server.takeRequest()
            assertEquals("GET", redirected.method)
            assertEquals(0L, redirected.bodySize)
            assertEquals(2, requests.get())
            assertEquals(2, responses.get())
          }
        }
      }
    }

  @Test
  fun `adapter repeatability restrictions survive native reconstruction for redirects and authentication recovery`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (repeatable in listOf(false, true)) {
          for (status in listOf(307, 303, 401)) {
            val requests = AtomicInteger()
            val opens = AtomicInteger()
            val observed = mutableListOf<Boolean>()
            val request =
              object : RequestAdapter {
                override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
                  requests.incrementAndGet()
                  val adapted =
                    if (status == 401) {
                      request.copy(headers = request.headers + ("Authorization" to "Bearer external"))
                    } else if (request.method == Method.Post) {
                      request.withBody(
                        StreamingBody.source {
                          opens.incrementAndGet()
                          Buffer().apply { writeString("body") }
                        },
                      )
                    } else {
                      request
                    }
                  return object : Request<TReq> by adapted {
                    override val isBodyRepeatable = repeatable
                  }
                }
              }
            val response =
              object : ResponseAdapter {
                override suspend fun <TRes> adapt(
                  response: Response<TRes>,
                  request: Request<*>,
                ): Response<TRes> {
                  observed += request.isBodyRepeatable
                  assertEquals(response.request.uri, request.uri)
                  assertEquals(response.request.headers, request.headers)
                  return response
                }
              }
            MockWebServer().use { server ->
              server.enqueue(
                MockResponse()
                  .setResponseCode(status)
                  .setHeader("Location", "/next")
                  .setHeader("WWW-Authenticate", "Bearer error=invalid_token")
                  .setBody("first"),
              )
              server.enqueue(MockResponse().setBody("next"))
              transport(URITemplate(server.url("/").toString()), request, response).use { transport ->
                val followUp = status != 401 && (repeatable || status == 303)
                val attempts = if (followUp) 2 else 1
                val result = perform(transport, streaming, if (status == 401) Method.Options else Method.Post)
                result.body!!.use { assertEquals(if (followUp) "next" else "first", it.readString()) }
                assertEquals(if (followUp) 200 else status, result.statusCode)
                assertEquals(attempts, requests.get())
                assertEquals(attempts, server.requestCount)
                assertEquals(List(attempts) { repeatable }, observed)
                assertEquals(
                  if (status == 401) {
                    0
                  } else if (status == 303) {
                    1
                  } else {
                    attempts
                  },
                  opens.get(),
                )
                if (status == 303) {
                  assertEquals("POST", server.takeRequest().method)
                  val redirected = server.takeRequest()
                  assertEquals("GET", redirected.method)
                  assertEquals(0L, redirected.bodySize)
                }
              }
            }
          }
        }
      }
    }

  @Test
  fun `bulk response collection preserves a large payload`() =
    runTest {
      val bytes = ByteArray(4 * 1024 * 1024) { it.toByte() }
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        transport(URITemplate(server.url("/").toString())).use { transport ->
          perform(transport, false).body!!.use { assertArrayEquals(bytes, it.readByteArray()) }
        }
      }
    }

  @Test
  fun `cancellation interrupts suspended response adaptation`() =
    runTest {
      val adapting = CompletableDeferred<Unit>()
      val cancelled = CompletableDeferred<Unit>()
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("body"))
        val response =
          object : ResponseAdapter {
            override suspend fun <TRes> adapt(
              response: Response<TRes>,
              request: Request<*>,
            ): Response<TRes> {
              adapting.complete(Unit)
              return try {
                awaitCancellation()
              } finally {
                cancelled.complete(Unit)
              }
            }
          }
        transport(URITemplate(server.url("/").toString()), responseAdapter = response).use { transport ->
          val call = async { perform(transport, false) }
          adapting.await()
          call.cancelAndJoin()
          cancelled.await()
          assertEquals(1, server.requestCount)
        }
      }
    }

  @Test
  fun `cancellation interrupts blocked bodies without concurrently closing buffered sources`() =
    runTest {
      withContext(Dispatchers.Default) {
        for (streaming in listOf(false, true)) {
          for (readInAdapter in listOf(false, true)) {
            val reading = CompletableDeferred<Unit>()
            val failure = CompletableDeferred<Throwable>()
            val inRead = AtomicBoolean()
            val concurrentClose = AtomicBoolean()
            MockWebServer().use { server ->
              // Send headers but never deliver the declared body; cancellation must unblock the read.
              server.enqueue(MockResponse().setHeader("Content-Length", "100"))
              val adapter =
                object : ResponseAdapter {
                  override suspend fun <TRes> adapt(
                    response: Response<TRes>,
                    request: Request<*>,
                  ): Response<TRes> {
                    val source = response.body!!
                    val observed =
                      object : RawSource {
                        override fun readAtMostTo(
                          sink: Buffer,
                          byteCount: Long,
                        ): Long {
                          inRead.set(true)
                          reading.complete(Unit)
                          try {
                            return source.readAtMostTo(sink, byteCount)
                          } finally {
                            inRead.set(false)
                          }
                        }

                        override fun close() {
                          if (inRead.get()) concurrentClose.set(true)
                          source.close()
                        }
                      }.buffered()
                    return if (readInAdapter) {
                      withContext(Dispatchers.IO) { observed.use { it.readString() } }
                      response.copy(body = Buffer())
                    } else {
                      response.copy(body = observed)
                    }
                  }
                }
              transport(URITemplate(server.url("/").toString()), responseAdapter = adapter).use { transport ->
                val call =
                  launch {
                    try {
                      perform(transport, streaming).body?.close()
                      fail("The stalled body unexpectedly completed")
                    } catch (error: Throwable) {
                      failure.complete(error)
                    }
                  }
                reading.await()
                withTimeout(2000) { call.cancelAndJoin() }
                assertInstanceOf(CancellationException::class.java, failure.await())
                assertFalse(concurrentClose.get())
                assertEquals(1, server.requestCount)
              }
            }
          }
        }
      }
    }

  @Test
  fun `cancellation follows replacement responses through composed adapters with reader owned cleanup`() =
    runTest {
      withContext(Dispatchers.Default) {
        for (streaming in listOf(false, true)) {
          for (readInAdapter in listOf(false, true)) {
            val reading = CompletableDeferred<Unit>()
            val failure = CompletableDeferred<Throwable>()
            val released = CountDownLatch(1)
            val inRead = AtomicBoolean()
            val concurrentClose = AtomicBoolean()
            val closes = AtomicInteger()
            val cancellations = AtomicInteger()
            val staleCancellations = AtomicInteger()
            val laterAdapters = AtomicInteger()
            val source =
              object : RawSource {
                override fun readAtMostTo(
                  sink: Buffer,
                  byteCount: Long,
                ): Long {
                  inRead.set(true)
                  reading.complete(Unit)
                  try {
                    check(released.await(5, TimeUnit.SECONDS)) { "Replacement read was not cancelled" }
                    throw IOException("Replacement I/O cancelled")
                  } finally {
                    inRead.set(false)
                  }
                }

                override fun close() {
                  if (inRead.get()) concurrentClose.set(true)
                  closes.incrementAndGet()
                }
              }.buffered()
            val adapters =
              object : ResponseAdapter {
                override suspend fun <TRes> adapt(
                  response: Response<TRes>,
                  request: Request<*>,
                ): Response<TRes> {
                  response.body?.close()
                  return object : Response<TRes> by response {
                    override val body = Buffer()

                    override fun cancel() {
                      staleCancellations.incrementAndGet()
                    }
                  }
                }
              }.chainTo(
                object : ResponseAdapter {
                  override suspend fun <TRes> adapt(
                    response: Response<TRes>,
                    request: Request<*>,
                  ): Response<TRes> {
                    response.body?.close()
                    return object : Response<TRes> by response {
                      override val body = source

                      override fun cancel() {
                        cancellations.incrementAndGet()
                        released.countDown()
                      }
                    }
                  }
                },
              ).chainTo(
                object : ResponseAdapter {
                  override suspend fun <TRes> adapt(
                    response: Response<TRes>,
                    request: Request<*>,
                  ): Response<TRes> {
                    if (readInAdapter) withContext(Dispatchers.IO) { response.body!!.readString() }
                    return response
                  }
                },
              ).chainTo(
                object : ResponseAdapter {
                  override suspend fun <TRes> adapt(
                    response: Response<TRes>,
                    request: Request<*>,
                  ): Response<TRes> {
                    laterAdapters.incrementAndGet()
                    return response
                  }
                },
              )
            MockWebServer().use { server ->
              server.enqueue(MockResponse().setBody("original"))
              transport(URITemplate(server.url("/").toString()), responseAdapter = adapters).use { transport ->
                val call =
                  launch {
                    try {
                      perform(transport, streaming).body?.close()
                      fail("The replacement body unexpectedly completed")
                    } catch (error: Throwable) {
                      failure.complete(error)
                    }
                  }
                try {
                  withTimeout(2000) { reading.await() }
                  withTimeout(2000) { call.cancelAndJoin() }
                  assertInstanceOf(CancellationException::class.java, failure.await())
                  assertEquals(1, cancellations.get())
                  assertEquals(0, staleCancellations.get())
                  assertEquals(1, closes.get())
                  assertFalse(concurrentClose.get())
                  assertEquals(if (readInAdapter) 0 else 1, laterAdapters.get())
                  assertEquals(1, server.requestCount)
                } finally {
                  // A regression must fail promptly rather than leaving a blocked test worker behind.
                  released.countDown()
                  call.cancelAndJoin()
                }
              }
            }
          }
        }
      }
    }

  @Test
  fun `replacement returned during cancellation is closed before the next adapter`() =
    runTest {
      for (streaming in listOf(false, true)) {
        val closes = AtomicInteger()
        val replacement =
          object : RawSource {
            override fun readAtMostTo(
              sink: Buffer,
              byteCount: Long,
            ): Long = fail("Cancelled replacement must not be read")

            override fun close() {
              closes.incrementAndGet()
            }
          }.buffered()
        val adapters =
          object : ResponseAdapter {
            override suspend fun <TRes> adapt(
              response: Response<TRes>,
              request: Request<*>,
            ): Response<TRes> {
              response.body?.close()
              currentCoroutineContext().cancel()
              return response.copy(body = replacement)
            }
          }.chainTo(
            object : ResponseAdapter {
              override suspend fun <TRes> adapt(
                response: Response<TRes>,
                request: Request<*>,
              ): Response<TRes> = fail("Later adapter ran after cancellation")
            },
          )
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setBody("original"))
          transport(URITemplate(server.url("/").toString()), responseAdapter = adapters).use { transport ->
            val failure = runCatching { perform(transport, streaming) }.exceptionOrNull()
            assertInstanceOf(CancellationException::class.java, failure)
            assertEquals(1, closes.get())
            assertEquals(1, server.requestCount)
          }
        }
      }
    }

  @Test
  fun `cancellation observer starts before unconfined response adaptation blocks`() =
    runTest {
      withContext(Dispatchers.Default) {
        val reading = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<Throwable>()
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setBody("delayed").setBodyDelay(5, TimeUnit.SECONDS))
          val adapter =
            object : ResponseAdapter {
              override suspend fun <TRes> adapt(
                response: Response<TRes>,
                request: Request<*>,
              ): Response<TRes> {
                reading.complete(Unit)
                val body = response.body!!.use { it.readString() }
                return response.copy(body = Buffer().apply { writeString(body) })
              }
            }
          transport(URITemplate(server.url("/").toString()), responseAdapter = adapter).use { transport ->
            val call =
              launch(Dispatchers.Unconfined) {
                try {
                  perform(transport, false).body?.close()
                  fail("The delayed body unexpectedly completed")
                } catch (error: Throwable) {
                  failure.complete(error)
                }
              }
            withTimeout(2000) { reading.await() }
            withTimeout(2000) { call.cancelAndJoin() }
            assertInstanceOf(CancellationException::class.java, failure.await())
          }
        }
      }
    }

  @Test
  @Tag("constrained-io")
  fun `buffered cancellation does not need an available IO worker`() = constrainedCancellation(false)

  @Test
  @Tag("constrained-io")
  fun `streaming cancellation does not need an available IO worker`() = constrainedCancellation(true)

  private fun constrainedCancellation(streaming: Boolean) =
    runTest {
      assertEquals("1", System.getProperty("kotlinx.coroutines.io.parallelism"))
      withContext(Dispatchers.Default) {
        val reading = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<Throwable>()
        val inRead = AtomicBoolean()
        val concurrentClose = AtomicBoolean()
        MockWebServer().use { server ->
          // Bound a broken implementation's hang, but never let network progress satisfy the deadline.
          server.enqueue(MockResponse().setBody("delayed").setBodyDelay(5, TimeUnit.SECONDS))
          val adapter =
            object : ResponseAdapter {
              override suspend fun <TRes> adapt(
                response: Response<TRes>,
                request: Request<*>,
              ): Response<TRes> {
                val source = response.body!!
                return response.copy(
                  body =
                    object : RawSource {
                      override fun readAtMostTo(
                        sink: Buffer,
                        byteCount: Long,
                      ): Long {
                        inRead.set(true)
                        reading.complete(Unit)
                        try {
                          return source.readAtMostTo(sink, byteCount)
                        } finally {
                          inRead.set(false)
                        }
                      }

                      override fun close() {
                        if (inRead.get()) concurrentClose.set(true)
                        source.close()
                      }
                    }.buffered(),
                )
              }
            }
          transport(URITemplate(server.url("/").toString()), responseAdapter = adapter).use { transport ->
            val call =
              launch {
                try {
                  perform(transport, streaming).body?.close()
                  fail("The delayed body unexpectedly completed")
                } catch (error: Throwable) {
                  failure.complete(error)
                }
              }
            withTimeout(2000) { reading.await() }
            withTimeout(2000) { call.cancelAndJoin() }
            assertInstanceOf(CancellationException::class.java, failure.await())
            assertFalse(concurrentClose.get())
          }
        }
      }
    }

  @Test
  fun `body read stop exceptions retain their identity in both execution modes`() =
    runTest {
      for (streaming in listOf(false, true)) {
        val stop = ReadStop()
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setBody("discard"))
          val adapter =
            object : ResponseAdapter {
              override suspend fun <TRes> adapt(
                response: Response<TRes>,
                request: Request<*>,
              ): Response<TRes> {
                response.body?.close()
                return response.copy(
                  body =
                    object : RawSource {
                      override fun readAtMostTo(
                        sink: Buffer,
                        byteCount: Long,
                      ): Long = throw stop

                      override fun close() = Unit
                    }.buffered(),
                )
              }
            }
          transport(URITemplate(server.url("/").toString()), responseAdapter = adapter).use { transport ->
            assertSame(stop, runCatching { perform(transport, streaming) }.exceptionOrNull())
            assertEquals(1, server.requestCount)
          }
        }
      }
    }

  @Test
  fun `body cleanup preserves the original failure and does not swallow stop signals`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (failReading in listOf(false, true)) {
          val readStop = Stop()
          val closeStop = Stop()
          MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("discard"))
            val adapter =
              object : ResponseAdapter {
                override suspend fun <TRes> adapt(
                  response: Response<TRes>,
                  request: Request<*>,
                ): Response<TRes> {
                  response.body?.close()
                  return response.copy(
                    body =
                      object : RawSource {
                        override fun readAtMostTo(
                          sink: Buffer,
                          byteCount: Long,
                        ): Long = if (failReading) throw readStop else -1L

                        override fun close(): Unit = throw closeStop
                      }.buffered(),
                  )
                }
              }
            transport(URITemplate(server.url("/").toString()), responseAdapter = adapter).use { transport ->
              val error = runCatching { perform(transport, streaming) }.exceptionOrNull()
              assertSame(if (failReading) readStop else closeStop, error)
              if (failReading) assertSame(closeStop, error!!.suppressed.single())
              assertEquals(1, server.requestCount)
            }
          }
        }
      }
    }

  @Test
  fun `token feedback runs once without requesting a transport retry`() =
    runTest {
      for (method in listOf(Method.Get, Method.Post)) {
        val rejected = AtomicInteger()
        val acquired = AtomicInteger()
        val provider =
          object : TokenProvider {
            override val identity = "external"

            override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

            override suspend fun acquire(request: TokenRequest) = TokenSet("token-${acquired.incrementAndGet()}")

            override suspend fun reject(
              request: TokenRequest,
              rejectedToken: String,
            ): Boolean {
              rejected.incrementAndGet()
              return true
            }
          }
        MockWebServer().use { server ->
          repeat(2) {
            server.enqueue(
              MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"),
            )
          }
          transport(
            URITemplate(server.url("/").toString()),
            providers = mapOf("identity" to provider),
          ).use { transport ->
            val binding =
              SecurityBinding(
                "identity",
                "identity",
                SecurityBinding.Flow.External,
                transport =
                  SecurityBinding.CredentialTransport(
                    SecurityBinding.CredentialTransport.Location.Header,
                    "Authorization",
                    "Bearer",
                  ),
              )
            transport
              .withSecurity(listOf(binding))
              .transportResponse(method, "value")
              .body
              ?.close()
            val attempts = 1
            assertEquals(attempts, acquired.get())
            assertEquals(attempts, rejected.get())
            assertEquals(attempts, server.requestCount)
          }
        }
      }
    }

  /** Public and copyable by coroutine debug recovery, unlike the private fixture exceptions. */
  class PublicStop(
    message: String,
  ) : RuntimeException(message),
    RequestCancellationException

  /** Stop signals may themselves be the asynchronous wrappers a native client returns. */
  class CompletionStop(
    cause: Throwable,
  ) : CompletionException(cause),
    RequestCancellationException

  /** Execution-result wrappers retain their stop semantics even with an ordinary cause. */
  class ExecutionStop(
    cause: Throwable,
  ) : ExecutionException(cause),
    RequestCancellationException

  @Test
  @Tag("wrapped-stop")
  fun `marked asynchronous upload failures retain stop signals through native callbacks`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (failOn in listOf(1, 2)) {
          for (stop in listOf(CompletionStop(IOException("cause")), ExecutionStop(IOException("cause")))) {
            val thrown = CompletionException(ExecutionException(stop))
            val requests = AtomicInteger()
            val opens = AtomicInteger()
            val responses = AtomicInteger()
            val request =
              object : RequestAdapter {
                override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
                  requests.incrementAndGet()
                  return request.withBody(
                    StreamingBody.source {
                      if (opens.incrementAndGet() == failOn) throw thrown
                      Buffer().apply { writeString("body") }
                    },
                  )
                }
              }
            val response =
              object : ResponseAdapter {
                override suspend fun <TRes> adapt(
                  response: Response<TRes>,
                  request: Request<*>,
                ): Response<TRes> {
                  responses.incrementAndGet()
                  return response
                }
              }
            MockWebServer().use { server ->
              if (failOn == 2) server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/next"))
              server.enqueue(MockResponse())
              transport(URITemplate(server.url("/").toString()), request, response).use { transport ->
                val failure = runCatching { perform(transport, streaming, Method.Post) }.exceptionOrNull()!!
                assertTrue(failure.isRequestCancelled)
                assertTrue(failure.causes().any { it === stop })
                assertSame(stop.cause, failure.causes().last())
                assertEquals(failOn, opens.get())
                assertEquals(failOn, requests.get())
                assertEquals(failOn - 1, responses.get())
              }
            }
          }
        }
      }
    }

  @Test
  @Tag("wrapped-stop")
  fun `public and wrapped adapter stops retain their causes without further work`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (inbound in listOf(false, true)) {
          for (stop in listOf(
            PublicStop("stop"),
            TokenRejectedException(),
            AuthorizationRequiredException(),
            CompletionStop(IOException("cause")),
            ExecutionStop(IOException("cause")),
          )) {
            for (wrapped in listOf(false, true)) {
              val thrown = if (wrapped) IOException("adapter", IllegalStateException("native", stop)) else stop
              MockWebServer().use { server ->
                if (inbound) server.enqueue(MockResponse().setBody("discard"))
                val outbound =
                  if (inbound) {
                    null
                  } else {
                    object : RequestAdapter {
                      override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> = throw thrown
                    }.chainTo(
                      object : RequestAdapter {
                        override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> =
                          fail("Later request adapter ran")
                      },
                    )
                  }
                val incoming =
                  if (!inbound) {
                    null
                  } else {
                    object : ResponseAdapter {
                      override suspend fun <TRes> adapt(
                        response: Response<TRes>,
                        request: Request<*>,
                      ): Response<TRes> = throw thrown
                    }.chainTo(
                      object : ResponseAdapter {
                        override suspend fun <TRes> adapt(
                          response: Response<TRes>,
                          request: Request<*>,
                        ): Response<TRes> = fail("Later response adapter ran")
                      },
                    )
                  }
                transport(URITemplate(server.url("/").toString()), outbound, incoming).use { transport ->
                  val failure = runCatching { perform(transport, streaming) }.exceptionOrNull()!!
                  assertTrue(failure.isRequestCancelled)
                  assertTrue(failure.causes().any { it === thrown })
                  assertTrue(failure.causes().any { it === stop })
                  assertEquals(if (inbound) 1 else 0, server.requestCount)
                }
              }
            }
          }
        }
      }
    }

  @Test
  @Tag("wrapped-stop")
  fun `wrapped acquisition and rejection stops are neither translated nor retried`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (duringRejection in listOf(false, true)) {
          for (temporary in listOf(false, true)) {
            val stop = PublicStop("external framework stopped")
            val thrown =
              if (temporary) {
                TokenProviderException(TokenProviderException.Reason.Temporary, cause = IOException("framework", stop))
              } else {
                IOException("framework", stop)
              }
            val acquisitions = AtomicInteger()
            val rejections = AtomicInteger()
            val provider =
              object : TokenProvider {
                override val identity = "external"

                override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

                override suspend fun acquire(request: TokenRequest): TokenSet {
                  acquisitions.incrementAndGet()
                  if (!duringRejection) throw thrown
                  return TokenSet("token")
                }

                override suspend fun reject(
                  request: TokenRequest,
                  rejectedToken: String,
                ): Boolean {
                  rejections.incrementAndGet()
                  throw thrown
                }
              }
            val binding =
              SecurityBinding(
                "identity",
                "identity",
                transport =
                  SecurityBinding.CredentialTransport(
                    SecurityBinding.CredentialTransport.Location.Header,
                    "Authorization",
                    "Bearer",
                  ),
              )
            MockWebServer().use { server ->
              server.enqueue(
                MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"),
              )
              transport(
                URITemplate(server.url("/").toString()),
                providers = mapOf("identity" to provider),
              ).use { transport ->
                val failure =
                  runCatching {
                    perform(
                      transport.withSecurity(listOf(binding)),
                      streaming,
                    )
                  }.exceptionOrNull()!!
                assertTrue(failure.causes().any { it === thrown })
                assertTrue(failure.causes().any { it === stop })
                assertEquals(1, acquisitions.get())
                assertEquals(if (duringRejection) 1 else 0, rejections.get())
                assertEquals(if (duringRejection) 1 else 0, server.requestCount)
              }
            }
          }
        }
      }
    }

  @Test
  @Tag("wrapped-stop")
  fun `wrapped stops veto SSE reconnect even when classified as temporary failures`() =
    runTest {
      for (temporary in listOf(false, true)) {
        val stop = PublicStop("stop events")
        val thrown =
          if (temporary) {
            TokenProviderException(TokenProviderException.Reason.Temporary, cause = IOException("framework", stop))
          } else {
            IOException("framework", stop)
          }
        MockWebServer().use { server ->
          server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: value\n\n"))
          transport(
            URITemplate(server.url("/").toString()),
            responseAdapter =
              object : ResponseAdapter {
                override suspend fun <TRes> adapt(
                  response: Response<TRes>,
                  request: Request<*>,
                ): Response<TRes> = throw thrown
              },
          ).use { transport ->
            val stream = transport.eventStream(Method.Get, "value", decoder = { _, _, _, data, _ -> data })
            val failure =
              runCatching {
                withContext(Dispatchers.Default) { withTimeout(2000) { stream.toList() } }
              }.exceptionOrNull()!!
            assertTrue(failure.causes().any { it === thrown })
            assertTrue(failure.causes().any { it === stop })
            assertEquals(1, server.requestCount)
          }
        }
      }
    }

  @Test
  fun `event subscriptions surface the original stop without reconnecting`() =
    runTest {
      val stop = Stop()
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: value\n\n"))
        transport(
          URITemplate(server.url("/").toString()),
          responseAdapter =
            object : ResponseAdapter {
              override suspend fun <TRes> adapt(
                response: Response<TRes>,
                request: Request<*>,
              ): Response<TRes> = throw stop
            },
        ).use { transport ->
          val stream = transport.eventStream(Method.Get, "value", decoder = { _, _, _, data, _ -> data })
          assertSame(stop, runCatching { stream.toList() }.exceptionOrNull())
          assertEquals(1, server.requestCount)
        }
      }
    }
}
