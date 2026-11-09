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
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.problems.SundayHttpProblem
import io.outfoxx.sunday.test.NativeRequestTypesTest
import org.junit.jupiter.api.Assertions.assertEquals

class OkHttpNativeRequestTypesTest : NativeRequestTypesTest<okhttp3.Request, okhttp3.Response>() {
  override fun transport(
    base: URITemplate,
    adapter: RequestAdapter,
  ) = OkHttpTransport(base, SundayHttpProblem.Factory, requestAdapter = adapter)

  override fun assertNativeResponse(response: okhttp3.Response) {
    assertEquals(200, response.code)
  }

  override fun assertNative(request: okhttp3.Request) {
    assertEquals("yes", request.header("X-Decorated"))
  }
}
