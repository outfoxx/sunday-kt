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

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant

/** Strict wire decoding, kept separate from session and issuer policy. */
internal object OAuthWire {
  private val mapper =
    ObjectMapper()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
  private val scopePattern = Regex("[\\x21\\x23-\\x5B\\x5D-\\x7E]+( [\\x21\\x23-\\x5B\\x5D-\\x7E]+)*")
  private val maxMillis = BigDecimal("9007199254740991")

  class Discovery(
    val issuer: String,
    val tokenUrl: String?,
    val authorizationUrl: String?,
    val methods: List<String>?,
  )

  class Success(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: BigDecimal?,
    val refreshToken: String?,
    val scope: String?,
  ) {
    fun tokens(
      scopes: Set<String>,
      clock: Clock,
    ): TokenSet {
      if (!tokenType.equals("bearer", true) || (scope != null && !scope.split(" ").containsAll(scopes))) fail()
      val expiry =
        expiresIn?.let {
          val millis = BigDecimal(clock.instant().toEpochMilli()).add(it.multiply(BigDecimal(1000)))
          if (it.signum() <= 0 || millis.abs() > maxMillis) fail()
          Instant.ofEpochMilli(millis.longValueExact())
        }
      return TokenSet(accessToken, expiry, refreshToken)
    }

    override fun toString() = "OAuthSuccess()"
  }

  class Error(
    val code: String,
  ) {
    override fun toString() = "OAuthError()"
  }

  fun discovery(body: String): Discovery {
    val data = document(body)
    val issuer = string(data, "issuer", true)!!
    val token = string(data, "token_endpoint")?.also { endpoint(it) }
    val authorization = string(data, "authorization_endpoint")?.also { endpoint(it) }
    for (name in listOf("jwks_uri", "registration_endpoint", "revocation_endpoint", "introspection_endpoint")) {
      string(data, name)?.also { endpoint(it) }
    }
    val methods =
      data.get("token_endpoint_auth_methods_supported")?.let { value ->
        if (!value.isArray || value.any { !it.isTextual }) fail()
        value.map { it.textValue() }
      }
    return Discovery(issuer, token, authorization, methods)
  }

  fun success(body: String): Success {
    val data = document(body)
    val expiry =
      data.get("expires_in")?.let {
        if (!it.isNumber) fail()
        it.decimalValue().also { number -> if (number.stripTrailingZeros().scale() > 0) fail() }
      }
    val scope = string(data, "scope")?.also { if (!scopePattern.matches(it)) fail() }
    return Success(
      string(data, "access_token", true)!!,
      string(data, "token_type", true)!!,
      expiry,
      string(data, "refresh_token"),
      scope,
    )
  }

  fun error(body: String): Error {
    val data = document(body)
    string(data, "error_description", allowEmpty = true)
    string(data, "error_uri")
    return Error(string(data, "error", true)!!)
  }

  fun endpoint(value: String?): URI {
    val uri = URI(value ?: fail())
    if (!uri.isAbsolute ||
      uri.host == null ||
      uri.userInfo != null ||
      uri.fragment != null ||
      (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")))
    ) {
      fail()
    }
    return uri
  }

  private fun document(body: String): JsonNode = mapper.readTree(body)?.also { if (!it.isObject) fail() } ?: fail()

  private fun string(
    data: JsonNode,
    name: String,
    required: Boolean = false,
    allowEmpty: Boolean = false,
  ): String? {
    val value = data.get(name) ?: return if (required) fail() else null
    if (!value.isTextual || (!allowEmpty && value.textValue().isEmpty())) fail()
    return value.textValue()
  }

  private fun fail(): Nothing = throw TokenProviderException()
}
