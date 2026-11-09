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

import io.outfoxx.sunday.causes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class TokenProviderCauseTest {
  @Test
  fun `provider and manager failures retain configuration acquisition and rejection causes`() =
    runTest {
      val binding =
        SecurityBinding(
          "oauth",
          "oauth",
          transport =
            SecurityBinding.CredentialTransport(
              SecurityBinding.CredentialTransport.Location.Header,
              "Authorization",
              "Bearer",
            ),
        )
      for (managed in listOf(false, true)) {
        for (phase in if (managed) listOf("configure", "acquire") else listOf("configure", "acquire", "reject")) {
          val original = IOException("provider unavailable")
          val provider =
            object : TokenProvider {
              override val identity = "test"

              override fun configure(binding: SecurityBinding): TokenConfiguration {
                if (phase == "configure") throw original
                return TokenConfiguration("client")
              }

              override suspend fun acquire(request: TokenRequest): TokenSet {
                if (phase == "acquire") throw original
                return TokenSet("test-token")
              }

              override suspend fun reject(
                request: TokenRequest,
                rejectedToken: String,
              ): Boolean = throw original
            }
          val manager = if (managed) TokenManager(mapOf("oauth" to provider), scope = this) else null
          try {
            val failure = runCatching { (manager ?: provider).credentials(binding).invalidate() }.exceptionOrNull()!!
            assertTrue(failure is TokenProviderException, "$managed/$phase")
            assertSame(original, failure.causes().last(), "$managed/$phase")
          } finally {
            manager?.close()
          }
        }
      }
    }
}
