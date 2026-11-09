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

package io.outfoxx.sunday.security

import io.outfoxx.sunday.StreamingBody
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Request
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readString
import kotlinx.io.writeString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.URI

class SecuredRequestTest {
  private class Representation(
    override val transport: String = "native",
    override val method: Method = Method.Post,
    override val uri: URI = URI("https://example.test/"),
    override val headers: Headers = emptyList(),
    private val content: StreamingBody? = null,
  ) : Request<String> {
    override suspend fun body(): Source? = content?.openSource()

    override fun copy(
      method: Method,
      uri: URI,
      headers: Headers,
    ): Request<String> = Representation(transport, method, uri, headers, content)

    override fun copy(transport: String): Request<String> = Representation(transport, method, uri, headers, content)

    override fun withBody(body: StreamingBody?): Request<String> = Representation(transport, method, uri, headers, body)
  }

  @Test
  fun `credential decoration preserves metadata across every representation copy`() =
    runTest {
      val original = Representation()
      val context = SecurityContext(emptyList())
      val secured: Request<String> = original.withSecurityContext(context)
      val changed =
        secured
          .copy(headers = listOf("X-Test" to "yes"))
          .copy(transport = "replacement")
          .withBody(StreamingBody.source { Buffer().apply { writeString("body") } })
      assertSame(context, changed.securityContext)
      assertFalse(changed.isBodyRepeatable)
      assertEquals("replacement", changed.transport)
      assertEquals(listOf("X-Test" to "yes"), changed.headers)
      changed.body()!!.use { assertEquals("body", it.readString()) }
      assertSame(original, secured.withSecurityContext(null))
      assertNull(changed.withSecurityContext(null).securityContext)
    }

  @Test
  fun `clearing credentials preserves an enclosing adapter decorator`() {
    val secured = Representation().withSecurityContext(SecurityContext(emptyList()))
    val decorated =
      object : Request<String> by secured {
        override val hasBody = false
      }
    val exported = decorated.withSecurityContext(null)
    assertNull(exported.securityContext)
    assertFalse(exported.hasBody)
    assertEquals("native", exported.transport)
  }
}
