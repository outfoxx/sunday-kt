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

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** OAuth exchange independent of HTTP implementation, with application-owned interactive authorization. */
class OAuthTokenProvider(
  private val configuration: Configuration,
  private val exchange: suspend (ExchangeRequest) -> ExchangeResponse,
) : TokenProvider.Refreshing {
  /** Application-owned OAuth client/session and independently configured discovery trust. */
  data class Configuration(
    val identity: String,
    val clientId: String,
    val clientSecret: String? = null,
    val authentication: Authentication = Authentication.None,
    val grantIdentity: String? = null,
    val authorization: (suspend (TokenRequest) -> AuthorizationGrant)? = null,
    val endpoints: SecurityEndpoints = SecurityEndpoints(),
    val issuer: String? = null,
    val clock: Clock = Clock.systemUTC(),
  ) {
    override fun toString(): String = "OAuthConfiguration(identity=$identity, clientId=$clientId)"
  }

  /** Supported token endpoint authentication; other methods can implement TokenProvider. */
  enum class Authentication(
    val wireName: String,
  ) {
    None("none"),
    ClientSecretBasic("client_secret_basic"),
    ClientSecretPost("client_secret_post"),
  }

  /** Native transports must send without redirects, ambient auth, or application cookies. */
  data class ExchangeRequest(
    val uri: URI,
    val headers: Map<String, String>,
    val form: Map<String, String>?,
  ) {
    override fun toString(): String = "OAuthExchangeRequest()"

    /** Encodes token request fields according to the OAuth form encoding rules. */
    fun encodedForm(): String? =
      form?.entries?.joinToString("&") {
        URLEncoder.encode(it.key, UTF_8.name()) + "=" + URLEncoder.encode(it.value, UTF_8.name())
      }
  }

  /** Buffered token/metadata response, excluded from diagnostic strings. */
  data class ExchangeResponse(
    val status: Int,
    val body: String,
  ) {
    override fun toString(): String = "OAuthExchangeResponse(status=$status)"
  }

  override val identity: String get() = configuration.identity
  private val mapper = ObjectMapper()
  private val consumedCodes = ConcurrentHashMap.newKeySet<String>()

  init {
    require(configuration.identity.isNotBlank() && configuration.clientId.isNotBlank()) {
      "OAuth identities must not be blank"
    }
    require((configuration.authentication == Authentication.None) == (configuration.clientSecret == null)) {
      "Client secrets require an explicit OAuth client authentication method"
    }
    require(configuration.clientSecret?.isEmpty() != true) { "Client secret must not be empty" }
  }

  override fun configure(binding: SecurityBinding): TokenConfiguration =
    TokenConfiguration(configuration.clientId, configuration.grantIdentity, configuration.endpoints)

  override suspend fun acquire(request: TokenRequest): TokenSet =
    safe {
      val resolved = resolveEndpoints(request)
      val form =
        when (request.binding.flow) {
          SecurityBinding.Flow.ClientCredentials -> {
            if (configuration.authentication == Authentication.None) throw TokenProviderException()
            mutableMapOf("grant_type" to "client_credentials")
          }
          SecurityBinding.Flow.AuthorizationCode -> {
            if (consumedCodes.size >= 1024) throw AuthorizationRequiredException()
            val grant = configuration.authorization?.invoke(resolved) ?: throw AuthorizationRequiredException()
            if (grant.code.isEmpty() ||
              grant.redirectUri.isEmpty() ||
              !grant.codeVerifier.matches(Regex("[A-Za-z0-9._~-]{43,128}"))
            ) {
              throw AuthorizationRequiredException()
            }
            val fingerprint =
              Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(grant.code.toByteArray(UTF_8)),
              )
            synchronized(consumedCodes) {
              if (consumedCodes.size >= 1024 || !consumedCodes.add(fingerprint)) throw AuthorizationRequiredException()
            }
            mutableMapOf(
              "grant_type" to "authorization_code",
              "code" to grant.code,
              "redirect_uri" to grant.redirectUri,
              "code_verifier" to grant.codeVerifier,
            )
          }
          else -> throw TokenProviderException()
        }
      exchangeTokens(resolved, resolved.binding.endpoints.tokenUrl, form)
    }

  override suspend fun refresh(
    request: TokenRequest,
    refreshToken: String,
  ): TokenSet =
    safe {
      val resolved = resolveEndpoints(request)
      exchangeTokens(
        resolved,
        resolved.binding.endpoints.refreshUrl ?: resolved.binding.endpoints.tokenUrl,
        mutableMapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
      )
    }

  private suspend fun resolveEndpoints(request: TokenRequest): TokenRequest {
    val endpoints = request.binding.endpoints
    val discoveryUrl = endpoints.discoveryUrl ?: return request
    val issuer = configuration.issuer ?: throw TokenProviderException()
    val response = exchange(ExchangeRequest(endpoint(discoveryUrl), mapOf("Accept" to "application/json"), null))
    checkAvailability(response.status)
    val document = mapper.readTree(response.body)
    if (response.status != 200 || !document.isObject || document.path("issuer").textValue() != issuer) {
      throw TokenProviderException()
    }
    val methods = document.get("token_endpoint_auth_methods_supported")
    if ((methods == null && configuration.authentication != Authentication.ClientSecretBasic) ||
      (
        methods != null &&
          (
            !methods.isArray ||
              methods.none {
                it.textValue() == configuration.authentication.wireName
              }
          )
      )
    ) {
      throw TokenProviderException()
    }
    val tokenUrl = endpoints.tokenUrl ?: document.path("token_endpoint").textValue() ?: throw TokenProviderException()
    val authorizationUrl = endpoints.authorizationUrl ?: document.path("authorization_endpoint").textValue()
    return request.copy(
      binding =
        request.binding.copy(
          endpoints = endpoints.copy(tokenUrl = tokenUrl, authorizationUrl = authorizationUrl),
        ),
    )
  }

  private suspend fun exchangeTokens(
    request: TokenRequest,
    url: String?,
    form: MutableMap<String, String>,
  ): TokenSet {
    val binding = request.binding
    if (binding.scopes.isNotEmpty()) form["scope"] = binding.scopes.joinToString(" ")
    binding.audience?.let { form["audience"] = it }
    binding.resource?.let { form["resource"] = it }
    val headers = mutableMapOf("Accept" to "application/json", "Content-Type" to "application/x-www-form-urlencoded")
    if (configuration.authentication == Authentication.ClientSecretBasic) {
      val value =
        URLEncoder.encode(configuration.clientId, UTF_8.name()) + ":" +
          URLEncoder.encode(configuration.clientSecret!!, UTF_8.name())
      headers["Authorization"] = "Basic " + Base64.getEncoder().encodeToString(value.toByteArray(UTF_8))
    } else {
      form["client_id"] = configuration.clientId
      if (configuration.authentication ==
        Authentication.ClientSecretPost
      ) {
        form["client_secret"] = configuration.clientSecret!!
      }
    }
    val response = exchange(ExchangeRequest(endpoint(url), headers, form))
    checkAvailability(response.status)
    val data = mapper.readTree(response.body)
    if (!data.isObject) throw TokenProviderException()
    if (response.status != 200) {
      when (data.path("error").textValue()) {
        "invalid_grant" -> {
          if (binding.flow == SecurityBinding.Flow.AuthorizationCode) throw AuthorizationRequiredException()
          throw TokenProviderException(TokenProviderException.Reason.InvalidGrant)
        }
        "temporarily_unavailable", "server_error" -> throw TokenProviderException(
          TokenProviderException.Reason.Temporary,
        )
        else -> throw TokenProviderException()
      }
    }
    val accessToken =
      data.path("access_token").textValue()?.takeIf { it.isNotEmpty() } ?: throw TokenProviderException()
    if (!data.path("token_type").textValue().equals("bearer", true)) throw TokenProviderException()
    val expiresAt =
      data.get("expires_in")?.let {
        if (!it.isNumber || it.decimalValue().signum() <= 0) throw TokenProviderException()
        configuration.clock.instant().plusNanos(
          it
            .decimalValue()
            .movePointRight(9)
            .toBigInteger()
            .longValueExact(),
        )
      }
    val refreshToken =
      data.get("refresh_token")?.let {
        it.textValue()?.takeIf(String::isNotEmpty) ?: throw TokenProviderException()
      }
    data.get("scope")?.let {
      val scopes = it.textValue()?.split(" ") ?: throw TokenProviderException()
      if (!scopes.containsAll(binding.scopes)) throw TokenProviderException()
    }
    return TokenSet(accessToken, expiresAt, refreshToken)
  }

  private fun endpoint(value: String?): URI {
    val uri = URI(value ?: throw TokenProviderException())
    if (!uri.isAbsolute ||
      uri.host == null ||
      uri.userInfo != null ||
      uri.fragment != null ||
      (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")))
    ) {
      throw TokenProviderException()
    }
    return uri
  }

  private fun checkAvailability(status: Int) {
    if (status == 408 || status == 429 || status in 500..599) {
      throw TokenProviderException(TokenProviderException.Reason.Temporary)
    }
  }

  private suspend fun <T> safe(action: suspend () -> T): T =
    try {
      action()
    } catch (error: CancellationException) {
      throw error
    } catch (error: AuthorizationRequiredException) {
      throw error
    } catch (error: TokenProviderException) {
      throw error
    } catch (_: JsonProcessingException) {
      throw TokenProviderException()
    } catch (_: IOException) {
      throw TokenProviderException(TokenProviderException.Reason.Temporary)
    } catch (_: Exception) {
      throw TokenProviderException()
    }
}
