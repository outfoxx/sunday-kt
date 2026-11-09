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

import io.outfoxx.sunday.RequestCancellationException
import io.outfoxx.sunday.causes
import io.outfoxx.sunday.isRequestCancelled
import io.outfoxx.sunday.security.OAuthTokenProvider
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.SecurityEndpoints
import io.outfoxx.sunday.security.TokenProviderException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class JdkOAuthFailureTest {
  class Stop(
    cause: Throwable,
  ) : CompletionException(cause),
    RequestCancellationException

  @ParameterizedTest(name = "immediate completion = {0}")
  @ValueSource(booleans = [false, true])
  fun `oauth preserves marked completion failures and ordinary causes`(immediate: Boolean) =
    runTest {
      for (original in listOf(Stop(IOException("native failure")), IOException("provider unavailable"))) {
        val future = CompletableFuture<HttpResponse<String>>()
        if (immediate) future.completeExceptionally(original)
        FailedClient(future).use { client ->
          val provider =
            JdkOAuthTokenProvider(
              OAuthTokenProvider.Configuration(
                identity = "test",
                clientId = "client",
                clientSecret = "test-secret",
                authentication = OAuthTokenProvider.Authentication.ClientSecretPost,
                endpoints = SecurityEndpoints(tokenUrl = "https://issuer.test/token"),
              ),
              client,
            )
          val binding =
            SecurityBinding(
              "oauth",
              "oauth",
              flow = SecurityBinding.Flow.ClientCredentials,
              transport =
                SecurityBinding.CredentialTransport(
                  SecurityBinding.CredentialTransport.Location.Header,
                  "Authorization",
                  "Bearer",
                ),
            )
          val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { provider.credentials(binding) } }
          if (!immediate) future.completeExceptionally(original)
          val failure = result.await().exceptionOrNull()!!
          assertTrue(failure.causes().any { it === original })
          assertEquals(original is RequestCancellationException, failure.isRequestCancelled)
          if (original !is RequestCancellationException) {
            assertEquals(TokenProviderException.Reason.Temporary, (failure as TokenProviderException).reason)
          }
          assertEquals(1, client.calls)
        }
      }
    }

  private class FailedClient(
    private val future: CompletableFuture<HttpResponse<String>>,
  ) : HttpClient() {
    var calls = 0
    private val delegate = newBuilder().build()

    override fun cookieHandler() = delegate.cookieHandler()

    override fun connectTimeout() = delegate.connectTimeout()

    override fun followRedirects() = Redirect.NEVER

    override fun proxy() = delegate.proxy()

    override fun sslContext() = delegate.sslContext()

    override fun sslParameters() = delegate.sslParameters()

    override fun authenticator() = delegate.authenticator()

    override fun version() = delegate.version()

    override fun executor() = delegate.executor()

    override fun <T> send(
      request: HttpRequest,
      handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = error("Unexpected synchronous send")

    @Suppress("UNCHECKED_CAST")
    override fun <T> sendAsync(
      request: HttpRequest,
      handler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
      calls++
      return future as CompletableFuture<HttpResponse<T>>
    }

    override fun <T> sendAsync(
      request: HttpRequest,
      handler: HttpResponse.BodyHandler<T>,
      push: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = sendAsync(request, handler)

    override fun close() = delegate.close()
  }
}
