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

package io.outfoxx.sunday.okhttp

import io.outfoxx.sunday.RequestAdapter
import io.outfoxx.sunday.RequestCancellationException
import io.outfoxx.sunday.ResponseAdapter
import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.ResponseEvent
import io.outfoxx.sunday.http.copy
import io.outfoxx.sunday.problems.SundayHttpProblem
import io.outfoxx.sunday.security.AuthenticationRecoveryBudget
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.TokenConfiguration
import io.outfoxx.sunday.security.TokenProvider
import io.outfoxx.sunday.security.TokenRequest
import io.outfoxx.sunday.security.TokenSet
import io.outfoxx.sunday.test.AdapterExecutionTest
import io.outfoxx.sunday.withSecurity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class OkHttpAdapterExecutionTest : AdapterExecutionTest<okhttp3.Request, okhttp3.Response>() {
  override suspend fun executeExport(request: io.outfoxx.sunday.http.Request<okhttp3.Request>): Int =
    okhttp3
      .OkHttpClient()
      .newCall(request.transport)
      .execute()
      .use { it.code }

  override fun transport(
    base: URITemplate,
    requestAdapter: RequestAdapter?,
    responseAdapter: ResponseAdapter?,
    providers: Map<String, TokenProvider>,
  ) = OkHttpTransport(
    base,
    SundayHttpProblem.Factory,
    requestAdapter = requestAdapter,
    responseAdapter = responseAdapter,
    tokenProviders = providers,
  )

  @Test
  fun `response copies retain trailers after body consumption`() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(
          MockResponse()
            .setChunkedBody("body", 4)
            .setTrailers(Headers.Builder().add("X-Checksum", "value").build()),
        )
        transport(
          URITemplate(server.url("/").toString()),
          null,
          object : ResponseAdapter {
            override suspend fun <TRes> adapt(
              response: Response<TRes>,
              request: Request<*>,
            ): Response<TRes> = response.copy(statusCode = 202)
          },
          emptyMap(),
        ).use { transport ->
          val response = transport.transportResponse(Method.Get, "value")
          assertEquals(202, response.statusCode)
          assertTrue(response.trailers!!.any { it == "X-Checksum" to "value" })
          response.body?.close()
        }
      }
    }

  @Test
  fun `trailer inspection before reading does not consume the response body`() =
    runTest {
      for (streaming in listOf(false, true)) {
        MockWebServer().use { server ->
          server.enqueue(
            MockResponse()
              .setChunkedBody("body", 4)
              .setTrailers(Headers.Builder().add("X-Checksum", "value").build()),
          )
          transport(
            URITemplate(server.url("/").toString()),
            responseAdapter =
              object : ResponseAdapter {
                override suspend fun <TRes> adapt(
                  response: Response<TRes>,
                  request: Request<*>,
                ): Response<TRes> {
                  assertNull(response.trailers)
                  return response
                }
              },
          ).use { transport ->
            val response = perform(transport, streaming)
            response.body!!.use { assertEquals("body", it.readString()) }
            assertTrue(response.trailers!!.any { it == "X-Checksum" to "value" })
          }
        }
      }
    }

  @Test
  fun `wire field copies retain native bodies for custom methods`() {
    for (method in listOf("MERGE", "DELETE")) {
      val body = "body".toRequestBody()
      val native =
        okhttp3.Request
          .Builder()
          .url("https://example.test/")
          .method(method, body)
          .build()
      val original = OkHttpRequest(native)
      val copied = original.copy(headers = original.headers + ("X-Test" to "value"))
      assertEquals(method, copied.transport.method)
      assertSame(body, copied.transport.body)
      assertEquals("value", copied.transport.header("X-Test"))
    }
  }

  @Test
  fun `redirects require repeatability only when retaining the body`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (length in listOf(0L, 7L, -1L)) {
          for ((oneShot, duplex) in listOf(false to false, true to false, false to true)) {
            for (status in listOf(307, 303)) {
              val writes = AtomicInteger()
              val body = recordingBody(length, oneShot, duplex, writes)
              val client =
                OkHttpClient
                  .Builder()
                  .apply {
                    if (duplex) protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                  }.build()
              MockWebServer().use { server ->
                if (duplex) server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
                server.enqueue(
                  MockResponse().setResponseCode(status).setHeader("Location", "/next").setBody("redirect"),
                )
                server.enqueue(MockResponse().setBody("accepted"))
                val request =
                  OkHttpRequest(
                    okhttp3.Request
                      .Builder()
                      .url(server.url("/first"))
                      .post(body)
                      .build(),
                  )
                OkHttpTransport(URITemplate(server.url("/").toString()), SundayHttpProblem.Factory, httpClient = client)
                  .use { transport ->
                    val response = performNative(transport, request, streaming)
                    val follows = status == 303 || (!oneShot && !duplex)
                    assertEquals(if (follows) 200 else status, response.statusCode)
                    response.body?.close()
                    assertEquals(if (follows) 2 else 1, server.requestCount)
                    assertEquals(if (follows && status == 307) 2 else 1, writes.get())
                    assertEquals(if (length == 0L) "" else "payload", server.takeRequest().body.readUtf8())
                    if (follows) {
                      val redirected = server.takeRequest()
                      assertEquals(if (status == 303) "GET" else "POST", redirected.method)
                      assertEquals(if (status == 303 || length == 0L) "" else "payload", redirected.body.readUtf8())
                    }
                  }
              }
              client.dispatcher.executorService.shutdown()
              client.connectionPool.evictAll()
            }
          }
        }
      }
    }

  @Test
  fun `authentication recovery never repeats a zero-length one-shot or duplex body`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for ((oneShot, duplex) in listOf(false to false, true to false, false to true)) {
          val writes = AtomicInteger()
          val body = recordingBody(0, oneShot, duplex, writes)
          val client =
            OkHttpClient
              .Builder()
              .apply {
                if (duplex) protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
              }.build()
          MockWebServer().use { server ->
            if (duplex) server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
            server.enqueue(
              MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"),
            )
            server.enqueue(MockResponse().setBody("accepted"))
            val request =
              OkHttpRequest(
                okhttp3.Request
                  .Builder()
                  .url(server.url("/value"))
                  .method("OPTIONS", body)
                  .header("Authorization", "Bearer external")
                  .build(),
              )
            OkHttpTransport(URITemplate(server.url("/").toString()), SundayHttpProblem.Factory, httpClient = client)
              .use { transport ->
                val response = performNative(transport, request, streaming)
                val repeatable = !oneShot && !duplex
                assertEquals(if (repeatable) 200 else 401, response.statusCode)
                response.body?.close()
                assertEquals(if (repeatable) 2 else 1, server.requestCount)
                assertEquals(if (repeatable) 2 else 1, writes.get())
              }
          }
          client.dispatcher.executorService.shutdown()
          client.connectionPool.evictAll()
        }
      }
    }

  private fun recordingBody(
    length: Long,
    oneShot: Boolean,
    duplex: Boolean,
    writes: AtomicInteger,
  ): RequestBody =
    object : RequestBody() {
      override fun contentType(): okhttp3.MediaType? = null

      override fun contentLength() = length

      override fun isOneShot() = oneShot

      override fun isDuplex() = duplex

      override fun writeTo(sink: BufferedSink) {
        writes.incrementAndGet()
        if (length != 0L) sink.writeUtf8("payload")
        if (duplex) sink.close()
      }
    }

  private suspend fun performNative(
    transport: OkHttpTransport,
    request: OkHttpRequest,
    streaming: Boolean,
  ): Response<*> =
    if (streaming) {
      transport
        .start(request, AuthenticationRecoveryBudget())
        .toList()
        .filterIsInstance<ResponseEvent.Start<*>>()
        .single()
        .value
    } else {
      transport.transportResponse(request)
    }

  @Test
  fun `native retries respect adapter repeatability during buffered execution`() = checkNativeRepeatability(false)

  @Test
  fun `native retries respect adapter repeatability during streaming execution`() = checkNativeRepeatability(true)

  private fun checkNativeRepeatability(streaming: Boolean) =
    runTest {
      for (repeatable in listOf(false, true)) {
        for (length in listOf(null, 0L, 7L)) {
          val payload = if (length == 0L) "" else "payload"
          val writes = AtomicInteger()
          val adaptations = AtomicInteger()
          val responses = mutableListOf<Int>()
          val network = mutableListOf<Int>()
          val nativeOneShot = mutableListOf<Boolean>()
          val client =
            OkHttpClient
              .Builder()
              .addNetworkInterceptor { chain ->
                nativeOneShot += chain.request().body!!.isOneShot()
                chain.proceed(chain.request()).also { network += it.code }
              }.build()
          try {
            MockWebServer().use { server ->
              server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("rejected"))
              server.enqueue(MockResponse().setBody("retried"))
              OkHttpTransport(
                URITemplate(server.url("/").toString()),
                SundayHttpProblem.Factory,
                httpClient = client,
                requestAdapter =
                  object : RequestAdapter {
                    override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
                      adaptations.incrementAndGet()
                      val withBody =
                        request.withBody(
                          StreamingBody.source(length) {
                            writes.incrementAndGet()
                            Buffer().apply { writeString(payload) }
                          },
                        )
                      return if (repeatable) withBody else RestrictedRequest(withBody)
                    }
                  },
                responseAdapter =
                  object : ResponseAdapter {
                    override suspend fun <TRes> adapt(
                      response: Response<TRes>,
                      request: Request<*>,
                    ): Response<TRes> {
                      responses += response.statusCode
                      return response
                    }
                  },
              ).use { transport ->
                val result = perform(transport, streaming, Method.Post)
                val expectedStatus = if (repeatable) 200 else 503
                val expectedSends = if (repeatable) 2 else 1
                assertEquals(expectedStatus, result.statusCode)
                result.body!!.use { assertEquals(if (repeatable) "retried" else "rejected", it.readString()) }
                assertEquals(expectedSends, server.requestCount)
                assertEquals(expectedSends, writes.get())
                assertEquals(1, adaptations.get())
                assertEquals(listOf(expectedStatus), responses)
                assertEquals(if (repeatable) listOf(503, 200) else listOf(503), network)
                assertEquals(List(expectedSends) { !repeatable }, nativeOneShot)
                repeat(expectedSends) { assertEquals(payload, server.takeRequest().body.readUtf8()) }
              }
            }
          } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
          }
        }
      }
    }

  @Test
  fun `native 503 recovery stays inside one Sunday attempt with unchanged observable statuses`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (method in listOf(Method.Get, Method.Post)) {
          for (finalStatus in listOf(200, 503)) {
            for (stopAtResponse in listOf(false, true)) {
              val stop = object : RuntimeException("stop"), RequestCancellationException {}
              val requests = AtomicInteger()
              val responses = mutableListOf<Int>()
              val network = mutableListOf<Int>()
              val application = mutableListOf<Int>()
              val events = mutableListOf<Int>()
              val client =
                OkHttpClient
                  .Builder()
                  .addInterceptor { chain ->
                    chain.proceed(chain.request()).also { application += it.code }
                  }.addNetworkInterceptor { chain ->
                    chain.proceed(chain.request()).also { network += it.code }
                  }.eventListener(
                    object : EventListener() {
                      override fun responseHeadersEnd(
                        call: Call,
                        response: okhttp3.Response,
                      ) {
                        events += response.code
                      }
                    },
                  ).build()
              MockWebServer().use { server ->
                server.enqueue(
                  MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("intermediate"),
                )
                server.enqueue(
                  MockResponse().setResponseCode(finalStatus).setHeader("Retry-After", "0").setBody("final"),
                )
                OkHttpTransport(
                  URITemplate(server.url("/").toString()),
                  SundayHttpProblem.Factory,
                  httpClient = client,
                  requestAdapter =
                    object : RequestAdapter {
                      override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> =
                        request.copy(
                          headers =
                            request.headers + ("X-Attempt" to requests.incrementAndGet().toString()),
                        )
                    },
                  responseAdapter =
                    object : ResponseAdapter {
                      override suspend fun <TRes> adapt(
                        response: Response<TRes>,
                        request: Request<*>,
                      ): Response<TRes> {
                        responses += response.statusCode
                        if (stopAtResponse) throw stop
                        return response
                      }
                    },
                ).use { transport ->
                  if (stopAtResponse) {
                    assertSame(stop, runCatching { perform(transport, streaming, method) }.exceptionOrNull())
                  } else {
                    val response = perform(transport, streaming, method)
                    assertEquals(finalStatus, response.statusCode)
                    response.body!!.use { assertEquals("final", it.readString()) }
                  }
                  assertEquals(2, server.requestCount)
                  repeat(2) { assertEquals("1", server.takeRequest().getHeader("X-Attempt")) }
                  assertEquals(1, requests.get())
                  assertEquals(listOf(finalStatus), responses)
                  assertEquals(listOf(finalStatus), application)
                  assertEquals(listOf(503, finalStatus), network)
                  assertEquals(listOf(503, finalStatus), events)
                }
              }
            }
          }
        }
      }
    }

  // Context-bearing application exceptions are not copied by coroutine debug stack-trace recovery.
  private class UploadFailure(
    val attempt: Int,
  ) : IOException("Upload failed at attempt $attempt")

  private class SourceFailure(
    val attempt: Int,
  ) : IllegalStateException("Source failed at attempt $attempt")

  @Test
  fun `native replay failures preserve original upload exceptions through executeAsync`() =
    runTest {
      for (streaming in listOf(false, true)) {
        val stop = object : RuntimeException("stop uploading"), RequestCancellationException {}
        for (failure in listOf(stop, UploadFailure(2), SourceFailure(2))) {
          val opens = AtomicInteger()
          MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse())
            OkHttpTransport(
              URITemplate(server.url("/").toString()),
              SundayHttpProblem.Factory,
              requestAdapter =
                object : RequestAdapter {
                  override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> =
                    request.withBody(
                      StreamingBody.source {
                        if (opens.incrementAndGet() == 2) throw failure
                        Buffer().apply { writeString("payload") }
                      },
                    )
                },
            ).use { transport ->
              assertSame(failure, runCatching { perform(transport, streaming, Method.Post) }.exceptionOrNull())
              assertEquals(2, opens.get())
              assertEquals("payload", server.takeRequest().body.readUtf8())
            }
          }
        }
      }
    }

  @Test
  fun `native 421 recovery replaces a coalesced HTTP2 connection inside one Sunday attempt`() =
    runTest {
      val certificate =
        HeldCertificate
          .Builder()
          .addSubjectAlternativeName("first.test")
          .addSubjectAlternativeName("second.test")
          .build()
      val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
      val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
      for (streaming in listOf(false, true)) {
        for (finalStatus in listOf(200, 421)) {
          val requests = AtomicInteger()
          val responses = mutableListOf<Int>()
          val network = mutableListOf<Int>()
          val client =
            OkHttpClient
              .Builder()
              .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
              .dns(
                object : Dns {
                  override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
                },
              ).addNetworkInterceptor { chain -> chain.proceed(chain.request()).also { network += it.code } }
              .build()
          MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            server.enqueue(MockResponse().setBody("warmup"))
            server.enqueue(MockResponse().setResponseCode(421).setBody("misdirected"))
            server.enqueue(MockResponse().setResponseCode(finalStatus).setBody("final"))
            val first =
              server
                .url("/")
                .newBuilder()
                .host("first.test")
                .build()
            client
              .newCall(
                okhttp3.Request
                  .Builder()
                  .url(first)
                  .build(),
              ).execute()
              .use {
                assertEquals(Protocol.HTTP_2, it.protocol)
                it.body.string()
              }
            assertEquals(0, server.takeRequest().sequenceNumber)
            network.clear()
            val second =
              server
                .url("/")
                .newBuilder()
                .host("second.test")
                .build()
            OkHttpTransport(
              URITemplate(second.toString()),
              SundayHttpProblem.Factory,
              httpClient = client,
              requestAdapter =
                object : RequestAdapter {
                  override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
                    requests.incrementAndGet()
                    return request
                  }
                },
              responseAdapter =
                object : ResponseAdapter {
                  override suspend fun <TRes> adapt(
                    response: Response<TRes>,
                    request: Request<*>,
                  ): Response<TRes> {
                    responses += response.statusCode
                    return response
                  }
                },
            ).use { transport ->
              val response = perform(transport, streaming)
              assertEquals(finalStatus, response.statusCode)
              response.body!!.use { assertEquals("final", it.readString()) }
              assertEquals(3, server.requestCount)
              assertTrue(
                server.takeRequest().sequenceNumber > 0,
                "First attempt must reuse the other origin's connection",
              )
              assertEquals(0, server.takeRequest().sequenceNumber, "Recovery must use a fresh connection")
              assertEquals(1, requests.get())
              assertEquals(listOf(finalStatus), responses)
              assertEquals(listOf(421, finalStatus), network)
            }
          }
        }
      }
    }

  @Test
  fun `token feedback matches the effective application and network interceptor credentials`() =
    runTest {
      for (streaming in listOf(false, true)) {
        for (network in listOf(false, true)) {
          for (replace in listOf(false, true)) {
            val rejected = mutableListOf<String>()
            val authentications = AtomicInteger()
            val provider =
              object : TokenProvider {
                override val identity = "external"

                override fun configure(binding: SecurityBinding) = TokenConfiguration("client")

                override suspend fun acquire(request: TokenRequest) = TokenSet("provider-token")

                override suspend fun reject(
                  request: TokenRequest,
                  rejectedToken: String,
                ): Boolean {
                  rejected += rejectedToken
                  return true
                }
              }
            val token = if (replace) "wire-token" else "provider-token"
            val rewrite =
              okhttp3.Interceptor { chain ->
                chain.proceed(
                  chain
                    .request()
                    .newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build(),
                )
              }
            val client =
              OkHttpClient
                .Builder()
                .authenticator { _, _ ->
                  authentications.incrementAndGet()
                  null
                }.apply {
                  if (network) addNetworkInterceptor(rewrite) else addInterceptor(rewrite)
                }.build()
            MockWebServer().use { server ->
              server.enqueue(
                MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer error=invalid_token"),
              )
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
              OkHttpTransport(
                URITemplate(server.url("/").toString()),
                SundayHttpProblem.Factory,
                httpClient = client,
                tokenProviders = mapOf("identity" to provider),
                responseAdapter =
                  object : ResponseAdapter {
                    override suspend fun <TRes> adapt(
                      response: Response<TRes>,
                      request: Request<*>,
                    ): Response<TRes> {
                      assertTrue(
                        request.headers.any {
                          it.first.equals("Authorization", true) &&
                            it.second == "Bearer $token"
                        },
                      )
                      return response
                    }
                  },
              ).use { transport ->
                val response = perform(transport.withSecurity(listOf(binding)), streaming, Method.Post)
                assertEquals(401, response.statusCode)
                response.body?.close()
                assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization"))
                assertEquals(if (replace) emptyList<String>() else listOf("provider-token"), rejected)
                assertEquals(0, authentications.get())
                assertEquals(1, server.requestCount)
              }
            }
          }
        }
      }
    }


  @Test
  fun `native authentication honors non-repeatable bodies during buffered execution`() =
    checkNativeAuthentication(false)

  @Test
  fun `native authentication honors non-repeatable bodies during streaming execution`() =
    checkNativeAuthentication(true)

  private fun checkNativeAuthentication(streaming: Boolean) =
    runTest {
      for (repeatable in listOf(false, true)) {
        for (challenge in listOf(false, true)) {
          for (length in listOf(null, 7L)) {
            val writes = AtomicInteger()
            val adaptations = AtomicInteger()
            val challenges = AtomicInteger()
            val responses = mutableListOf<Int>()
            MockWebServer().use { server ->
              if (challenge) {
                server.enqueue(
                  MockResponse()
                    .setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Basic realm=probe")
                    .setBody("challenge"),
                )
              }
              server.enqueue(MockResponse().setBody("done"))
              val client =
                OkHttpClient
                  .Builder()
                  .authenticator { _, response ->
                    challenges.incrementAndGet()
                    response.request
                      .newBuilder()
                      .header("Authorization", "Basic dXNlcjpwYXNzd29yZA==")
                      .build()
                  }.build()
              try {
                OkHttpTransport(
                  URITemplate(server.url("/").toString()),
                  SundayHttpProblem.Factory,
                  httpClient = client,
                  requestAdapter =
                    object : RequestAdapter {
                      override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
                        adaptations.incrementAndGet()
                        val withBody =
                          request.withBody(
                            StreamingBody.source(length) {
                              writes.incrementAndGet()
                              Buffer().apply { writeString("payload") }
                            },
                          )
                        return if (repeatable) withBody else RestrictedRequest(withBody)
                      }
                    },
                  responseAdapter =
                    object : ResponseAdapter {
                      override suspend fun <TRes> adapt(
                        response: Response<TRes>,
                        request: Request<*>,
                      ): Response<TRes> {
                        responses += response.statusCode
                        return response
                      }
                    },
                ).use { transport ->
                  val response = perform(transport, streaming, Method.Post)
                  val expectedStatus = if (challenge && !repeatable) 401 else 200
                  assertEquals(expectedStatus, response.statusCode)
                  val expectedBody = if (expectedStatus == 401) "challenge" else "done"
                  response.body!!.use { assertEquals(expectedBody, it.readString()) }
                  assertEquals(listOf(expectedStatus), responses)
                  assertEquals(if (challenge && repeatable) 2 else 1, server.requestCount)
                  assertEquals(if (challenge && repeatable) 2 else 1, writes.get())
                  assertEquals(1, adaptations.get())
                  assertEquals(if (challenge) 1 else 0, challenges.get())
                  assertEquals("payload", server.takeRequest().body.readUtf8())
                }
              } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
              }
            }
          }
        }
      }
    }
}
