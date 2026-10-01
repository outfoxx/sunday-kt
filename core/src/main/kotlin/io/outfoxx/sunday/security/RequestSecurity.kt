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

import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Method
import io.outfoxx.sunday.http.Response
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/** Credential attachment and conservative recovery shared by native HTTP adapters. */
class RequestSecurity(
  private val bindings: List<SecurityBinding>,
  private val manager: TokenManager?,
) {
  /** Complete credential set, retained only for request execution and conditional invalidation. */
  data class Credentials(
    val uri: URI,
    val headers: Headers,
    val leases: List<TokenLease>,
  ) {
    override fun toString(): String = "RequestCredentials()"
  }

  /** Attaches every selected credential after all providers succeed, preserving the input request. */
  suspend fun authorize(
    uri: URI,
    headers: Headers,
    previous: Credentials? = null,
  ): Credentials {
    if (bindings.isEmpty()) return Credentials(uri, headers, emptyList())
    val provider = manager ?: throw TokenProviderException()
    val names = mutableSetOf<Pair<SecurityBinding.CredentialTransport.Location, String>>()
    bindings.forEach { binding ->
      val transport = binding.transport
      val name =
        if (transport.location ==
          SecurityBinding.CredentialTransport.Location.Header
        ) {
          transport.name.lowercase()
        } else {
          transport.name
        }
      if (!names.add(transport.location to name)) throw TokenProviderException()
      if (previous == null &&
        when (transport.location) {
          SecurityBinding.CredentialTransport.Location.Header -> headers.any { it.first.equals(name, true) }
          SecurityBinding.CredentialTransport.Location.Query -> queryNames(uri).contains(name)
          SecurityBinding.CredentialTransport.Location.Cookie -> cookies(headers).any { it.first == name }
        }
      ) {
        throw TokenProviderException()
      }
    }
    val leases = bindings.map { provider.credentials(it) }
    var resultUri = uri
    var resultHeaders: Headers = headers.toList()
    bindings.zip(leases).forEach { (binding, lease) ->
      val transport = binding.transport
      val credential = transport.prefix?.let { "$it ${lease.tokens.accessToken}" } ?: lease.tokens.accessToken
      when (transport.location) {
        SecurityBinding.CredentialTransport.Location.Header ->
          resultHeaders =
            resultHeaders.setting(transport.name, credential)
        SecurityBinding.CredentialTransport.Location.Query ->
          resultUri =
            setQuery(resultUri, transport.name, credential)
        SecurityBinding.CredentialTransport.Location.Cookie -> {
          val values =
            cookies(resultHeaders).filterNot { it.first == transport.name } +
              (transport.name to encode(credential).replace("+", "%20"))
          resultHeaders = resultHeaders.setting("Cookie", values.joinToString("; ") { "${it.first}=${it.second}" })
        }
      }
    }
    return Credentials(resultUri, resultHeaders, leases)
  }

  /** Invalidates only bearer credentials after a safe, bodyless request receives an invalid-token challenge. */
  suspend fun recover(
    method: Method,
    hasBody: Boolean,
    credentials: Credentials,
    response: Response,
    budget: AuthenticationRecoveryBudget = AuthenticationRecoveryBudget(),
  ): Boolean {
    if (response.statusCode != 401 || hasBody || method !in setOf(Method.Get, Method.Head, Method.Options)) return false
    val challenge =
      response.headers
        .filter {
          it.first.equals(
            "WWW-Authenticate",
            true,
          )
        }.joinToString(",") { it.second }
    if (!BearerChallenge.isInvalidToken(challenge)) return false
    val leases =
      bindings
        .zip(credentials.leases)
        .filter { (binding, _) ->
          val transport = binding.transport
          transport.location == SecurityBinding.CredentialTransport.Location.Header &&
            transport.name.equals("Authorization", true) &&
            transport.prefix.equals("Bearer", true)
        }.map { it.second }
    if (leases.isEmpty() || !budget.consume()) return false
    val provider = manager ?: throw TokenProviderException()
    leases.forEach { provider.invalidate(it) }
    return true
  }

  /** Removes managed credentials before emitting request diagnostics. */
  fun redact(
    uri: URI,
    headers: Headers,
  ): Credentials {
    var resultUri = uri
    var resultHeaders: Headers = headers.toList()
    bindings.forEach { binding ->
      val transport = binding.transport
      when (transport.location) {
        SecurityBinding.CredentialTransport.Location.Header ->
          resultHeaders =
            resultHeaders.setting(transport.name, "[redacted]")
        SecurityBinding.CredentialTransport.Location.Query ->
          resultUri =
            setQuery(resultUri, transport.name, "[redacted]")
        SecurityBinding.CredentialTransport.Location.Cookie ->
          resultHeaders =
            resultHeaders.setting("Cookie", "[redacted]")
      }
    }
    return Credentials(resultUri, resultHeaders, emptyList())
  }

  private fun Headers.setting(
    name: String,
    value: String,
  ): Headers = filterNot { it.first.equals(name, true) } + (name to value)

  private fun cookies(headers: Headers): List<Pair<String, String>> =
    headers.filter { it.first.equals("Cookie", true) }.flatMap { (_, value) ->
      value.split(';').mapNotNull { entry ->
        val parts = entry.split('=', limit = 2)
        parts.takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
      }
    }

  private fun queryNames(uri: URI): Set<String> =
    uri.rawQuery
      .orEmpty()
      .split('&')
      .map {
        decode(it.substringBefore('='))
      }.toSet()

  private fun setQuery(
    uri: URI,
    name: String,
    value: String,
  ): URI {
    val entries =
      uri.rawQuery
        ?.split('&')
        .orEmpty()
        .filterNot { decode(it.substringBefore('=')) == name }
    val query = (entries + "${encode(name)}=${encode(value)}").joinToString("&")
    val prefix = uri.toASCIIString().substringBefore('#').substringBefore('?')
    return URI(prefix + "?" + query + (uri.rawFragment?.let { "#$it" } ?: ""))
  }

  private fun encode(value: String): String = URLEncoder.encode(value, UTF_8.name())

  private fun decode(value: String): String = URLDecoder.decode(value, UTF_8.name())

}
