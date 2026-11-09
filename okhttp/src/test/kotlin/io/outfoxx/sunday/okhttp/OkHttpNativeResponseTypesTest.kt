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

import io.outfoxx.sunday.ResponseAdapter
import io.outfoxx.sunday.http.OperationResponse
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.copy
import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class OkHttpNativeResponseTypesTest {
  @Test
  fun `response copies chains and decoded result wrappers retain native types`() =
    runTest {
      val adapter =
        object : ResponseAdapter {
          override suspend fun <TRes> adapt(
            response: Response<TRes>,
            request: Request<*>,
          ): Response<TRes> = response.copy(statusCode = response.statusCode + 1)
        }
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("body"))
        val transport =
          OkHttpTransport(
            io.outfoxx.sunday.URITemplate(server.url("/").toString()),
            io.outfoxx.sunday.problems.SundayHttpProblem.Factory,
          )
        val request =
          transport.transportRequest<Unit>(
            io.outfoxx.sunday.http.Method.Get,
            "value",
            purpose = io.outfoxx.sunday.Transport.RequestPurpose.Normal,
          )
        val original: Response<okhttp3.Response> = transport.transportResponse(request)
        try {
          val adapted: Response<okhttp3.Response> = adapter.chainTo(adapter).adapt(original, request)
          val native: okhttp3.Response = adapted.transport
          assertSame(original.transport, native)
          assertEquals(200, native.code)
          assertEquals(202, adapted.statusCode)
          val result: OperationResponse<String, okhttp3.Response> =
            OperationResponse(adapted.body!!.readString(), adapted)
          val copied: OperationResponse<String, okhttp3.Response> = result.copy(result = "decoded")
          assertEquals("body", result.result)
          assertEquals("decoded", copied.result)
          assertSame(native, copied.transport)
          assertEquals(202, copied.statusCode)
        } finally {
          original.body?.close()
          transport.close()
        }
      }
    }
}
