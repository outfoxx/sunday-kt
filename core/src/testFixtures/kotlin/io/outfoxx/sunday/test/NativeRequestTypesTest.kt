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

import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.NullifySpec
import io.outfoxx.sunday.OperationSpec
import io.outfoxx.sunday.RequestAdapter
import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.Transport
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.OperationResponse
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.ResponseEvent
import io.outfoxx.sunday.nullableOperation
import io.outfoxx.sunday.operation
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.withSecurity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

/** Native-type export and decorated-request contracts shared by both transports. */
abstract class NativeRequestTypesTest<TReq, TRes> {
  /** Creates a transport that uses the same portable adapter contract. */
  abstract fun transport(
    base: URITemplate,
    adapter: RequestAdapter,
  ): Transport<TReq, TRes>

  /** Interrogates a native request without casting its Sunday wrapper. */
  abstract fun assertNative(request: TReq)

  /** Interrogates a native response without casting its Sunday wrapper. */
  abstract fun assertNativeResponse(response: TRes)

  @Test
  fun `native response types survive secured operations and streaming`() =
    runTest {
      MockWebServer().use { server ->
        repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("body")) }
        transport(URITemplate(server.url("/").toString()), decorator).use { base ->
          val binding =
            SecurityBinding(
              "external",
              "external",
              transport =
                SecurityBinding.CredentialTransport(
                  SecurityBinding.CredentialTransport.Location.Header,
                  "Authorization",
                ),
            )
          val secured: Transport<TReq, TRes> = base.withSecurity(listOf(binding))
          val spec = OperationSpec<Unit>(Method.Get, "value", acceptTypes = listOf(MediaType.from("text/plain")))
          val operation = secured.operation<Unit, String, TReq, TRes>(spec)
          val raw: Response<TRes> = operation.transportResponse()
          assertNativeResponse(raw.transport)
          raw.body!!.use { assertEquals("body", it.readString()) }
          val decoded: OperationResponse<String, TRes> = operation.response()
          assertNativeResponse(decoded.transport)
          assertEquals("body", decoded.result)
          val nullable: OperationResponse<String, TRes> =
            secured
              .nullableOperation<Unit, String, TReq, TRes>(
                spec,
                NullifySpec(),
              ).response()
          assertNativeResponse(nullable.transport)
          assertEquals("body", nullable.result)
          val request = secured.transportRequest<Unit>(Method.Get, "value", purpose = Transport.RequestPurpose.Events)
          val events: Flow<ResponseEvent<TRes>> = secured.start(request)
          var starts = 0
          events.collect { event ->
            when (event) {
              is ResponseEvent.Start -> {
                val response: Response<TRes> = event.value
                assertNativeResponse(response.transport)
                starts++
              }
              is ResponseEvent.Data -> event.value.clear()
              is ResponseEvent.End -> Unit
            }
          }
          assertEquals(1, starts)
          assertEquals(4, server.requestCount)
        }
      }
    }

  @Test
  fun `decorated exports preserve native types across operation and security wrappers`() =
    runTest {
      var adaptations = 0
      val count =
        object : RequestAdapter {
          override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> {
            adaptations++
            return request
          }
        }
      MockWebServer().use { server ->
        transport(URITemplate(server.url("/").toString()), decorator.chainTo(count)).use { base ->
          val binding =
            SecurityBinding(
              "external",
              "external",
              transport =
                SecurityBinding.CredentialTransport(
                  SecurityBinding.CredentialTransport.Location.Header,
                  "Authorization",
                ),
            )
          val secured: Transport<TReq, TRes> = base.withSecurity(listOf(binding))
          val spec = OperationSpec<Unit>(Method.Get, "value")
          val exports: List<Request<TReq>> =
            listOf(
              base.transportRequest<Unit>(Method.Get, "value"),
              secured.transportRequest<Unit>(Method.Get, "value"),
              secured.operation<Unit, Unit, TReq, TRes>(spec).transportRequest(),
              secured.nullableOperation<Unit, Unit, TReq, TRes>(spec, NullifySpec()).transportRequest(),
            )
          val reauthorized: Request<TReq> = base.authorize(exports.first(), listOf(binding))
          assertNative(reauthorized.transport)
          assertEquals(5, adaptations)
          assertEquals(0, server.requestCount)
          for (exported in exports) {
            assertFalse(exported.isBodyRepeatable)
            assertNull(exported.securityContext)
            val native: TReq = exported.transport
            assertNative(native)
            val copied: Request<TReq> = exported.copy(transport = native)
            assertFalse(copied.isBodyRepeatable)
            assertNative(copied.transport)
            val withBody: Request<TReq> =
              copied.copy(method = Method.Options).withBody(
                StreamingBody.source { Buffer().apply { writeString("replacement") } },
              )
            assertFalse(withBody.isBodyRepeatable)
            withBody.body()!!.use { assertEquals("replacement", it.readString()) }
          }
        }
      }
    }

  @Test
  fun `decorated requests execute buffered`() = checkExecution(false)

  @Test
  fun `decorated requests execute streaming`() = checkExecution(true)

  private fun checkExecution(streaming: Boolean) =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("body"))
        transport(URITemplate(server.url("/").toString()), decorator).use { transport ->
          val request: Request<TReq> =
            transport.transportRequest<Unit>(
              Method.Get,
              "value",
              purpose = Transport.RequestPurpose.Normal,
            )
          if (streaming) {
            val events = transport.start(request).toList()
            assertEquals(
              200,
              events
                .filterIsInstance<ResponseEvent.Start<*>>()
                .single()
                .value.statusCode,
            )
            assertEquals(
              "body",
              events.filterIsInstance<ResponseEvent.Data>().joinToString("") { it.value.readString() },
            )
          } else {
            val response = transport.transportResponse(request)
            response.body!!.use { assertEquals("body", it.readString()) }
          }
          assertEquals("yes", server.takeRequest().getHeader("X-Decorated"))
          assertEquals(1, server.requestCount)
        }
      }
    }

  private class DecoratedRequest<TReq>(
    private val delegate: Request<TReq>,
  ) : Request<TReq> by delegate {
    override val isBodyRepeatable = false

    override fun copy(
      method: Method,
      uri: URI,
      headers: Headers,
    ): Request<TReq> = DecoratedRequest(delegate.copy(method, uri, headers))

    override fun copy(transport: TReq): Request<TReq> = DecoratedRequest(delegate.copy(transport))

    override fun withBody(body: StreamingBody?): Request<TReq> = DecoratedRequest(delegate.withBody(body))
  }

  companion object {
    // A single instance is shared across transports; its implementation has no native-type knowledge.
    private val decorator =
      object : RequestAdapter {
        override suspend fun <TReq> adapt(request: Request<TReq>): Request<TReq> =
          DecoratedRequest(request.copy(headers = request.headers + ("X-Decorated" to "yes")))
      }
  }
}
