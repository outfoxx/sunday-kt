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

import java.net.URI
import java.util.Base64
import java.util.Collections
import java.util.UUID

/** Immutable endpoint and security inputs supplied to the application's transport factory. */
class ClientSettings(
  val baseURL: URI,
  bindings: Map<String, List<SecurityBinding>> = emptyMap(),
  credentials: Map<String, Credentials> = emptyMap(),
) {
  /** Complete security alternatives indexed by operation identity. */
  val bindings: Map<String, List<SecurityBinding>> =
    Collections.unmodifiableMap(
      bindings.mapValues { (_, values) ->
        Collections.unmodifiableList(
          values.map {
            it.copy(
              scopes = Collections.unmodifiableSet(it.scopes.toSet()),
              endpoints =
                SecurityEndpoints(
                  resolveEndpoint(it.endpoints.discoveryUrl),
                  resolveEndpoint(it.endpoints.authorizationUrl),
                  resolveEndpoint(it.endpoints.tokenUrl),
                  resolveEndpoint(it.endpoints.refreshUrl),
                ),
            )
          },
        )
      },
    )
  private val credentials = credentials.toMap()

  init {
    require(baseURL.isAbsolute && baseURL.scheme.lowercase() in setOf("http", "https") && baseURL.host != null) {
      "Client endpoint must be an absolute HTTP or HTTPS URL"
    }
    this.bindings.values.flatten().forEach { binding ->
      validate(
        requireNotNull(this.credentials[binding.scheme]) {
          "Missing credentials for scheme '${binding.scheme}'"
        },
        binding,
      )
    }
  }

  /** Prepared manager shared by this configuration's operations, without acquiring tokens. */
  val tokenManager: TokenManager? = prepareTokenManager()

  private fun prepareTokenManager(): TokenManager? {
    val owners = mutableMapOf<String, String>()
    val providers = mutableMapOf<String, TokenProvider>()
    bindings.values.flatten().forEach { binding ->
      val owner = owners.putIfAbsent(binding.provider, binding.scheme)
      require(
        owner == null || owner == binding.scheme,
      ) { "Distinct credential schemes require distinct provider bindings" }
      if (owner == null) {
        providers[binding.provider] =
          when (val credential = credentials.getValue(binding.scheme)) {
            is ProviderCredentials -> credential.provider
            is OAuthCredentials ->
              credential.providerFactory(
                credential.configuration.copy(
                  endpoints =
                    credential.configuration.endpoints.let {
                      SecurityEndpoints(
                        resolveEndpoint(it.discoveryUrl),
                        resolveEndpoint(it.authorizationUrl),
                        resolveEndpoint(it.tokenUrl),
                        resolveEndpoint(it.refreshUrl),
                      )
                    },
                ),
              )
            else -> {
              val token =
                when (credential) {
                  is BearerCredentials -> credential.token
                  is ApiKeyCredentials -> credential.key
                  is BasicCredentials ->
                    Base64.getEncoder().encodeToString(
                      "${credential.username}:${credential.password}".toByteArray(),
                    )
                }
              object : TokenProvider {
                override val identity = UUID.randomUUID().toString()

                override fun configure(binding: SecurityBinding) = TokenConfiguration(identity)

                override suspend fun acquire(request: TokenRequest) = TokenSet(token)
              }
            }
          }
      }
    }
    return providers.takeIf { it.isNotEmpty() }?.let { TokenManager(it) }
  }

  private fun resolveEndpoint(value: String?): String? =
    value?.let {
      require('{' !in it && '}' !in it) { "Security endpoint URLs do not support server variables" }
      baseURL.resolve(it).toString()
    }

  companion object {
    /** Expands variables once and resolves relative servers against their document location. */
    fun serverURL(
      template: String,
      variables: Map<String, String>,
      documentBaseURL: URI? = null,
    ): URI {
      val expanded = Regex("\\{([^}]+)}").replace(template) { match -> variables.getValue(match.groupValues[1]) }
      val url = documentBaseURL?.resolve(expanded) ?: URI(expanded)
      require(
        url.scheme?.lowercase() in setOf("http", "https") &&
          url.host != null &&
          url.userInfo == null &&
          url.query == null &&
          url.fragment == null,
      ) {
        "Invalid HTTP server endpoint; relative servers require an HTTP document base URL"
      }
      return url
    }

    /** Selects complete operation alternatives, rejecting missing or ambiguous credentials before transport creation. */
    fun resolve(
      baseURL: URI,
      alternatives: Map<String, List<List<SecurityBinding>>>,
      credentials: Map<String, Credentials>,
      selection: Map<String, Set<String>> = emptyMap(),
    ): ClientSettings {
      val bindings =
        alternatives.mapValues { (operation, candidates) ->
          val usable =
            candidates.filter { candidate ->
              (selection[operation] == null || selection[operation] == candidate.map { it.scheme }.toSet()) &&
                runCatching {
                  candidate.forEach { binding -> validate(requireNotNull(credentials[binding.scheme]), binding) }
                }.isSuccess
            }
          require(usable.size == 1) { "Operation '$operation' requires one complete security alternative" }
          usable.single()
        }
      return ClientSettings(baseURL, bindings, credentials)
    }

    private fun validate(
      credential: Credentials,
      binding: SecurityBinding,
    ) {
      val prefix = binding.transport.prefix?.lowercase()
      when (credential) {
        is ProviderCredentials ->
          require(credential.flow == null || credential.flow == binding.flow) {
            "Provider flow does not match"
          }
        is OAuthCredentials -> {
          val config = credential.configuration
          val flow =
            when (credential) {
              is OAuthCredentials.ClientCredentials -> SecurityBinding.Flow.ClientCredentials
              is OAuthCredentials.AuthorizationCode -> SecurityBinding.Flow.AuthorizationCode
            }
          require(
            prefix == "bearer" && binding.flow == flow,
          ) { "OAuth credentials do not match the selected security binding" }
          require(config.identity.isNotBlank() && config.clientId.isNotBlank()) { "OAuth identities must not be blank" }
          require((config.authentication == OAuthTokenProvider.Authentication.None) == (config.clientSecret == null)) {
            "Client secrets require an explicit OAuth client authentication method"
          }
          require(config.clientSecret?.isEmpty() != true) { "Client secret must not be empty" }
          when (credential) {
            is OAuthCredentials.ClientCredentials ->
              require(
                config.authentication != OAuthTokenProvider.Authentication.None,
              ) {
                "Client credentials require client authentication"
              }
            is OAuthCredentials.AuthorizationCode ->
              require(
                !config.grantIdentity.isNullOrBlank() && config.authorization != null,
              ) {
                "Authorization code credentials require a fresh application authorization session"
              }
          }
        }
        else -> {
          require(binding.flow in setOf(SecurityBinding.Flow.Static, SecurityBinding.Flow.External)) {
            "Static credentials cannot satisfy an OAuth acquisition binding"
          }
          require(
            when (credential) {
              is BearerCredentials -> prefix == "bearer" && credential.token.isNotEmpty()
              is ApiKeyCredentials -> prefix == null && credential.key.isNotEmpty()
              is BasicCredentials -> prefix == "basic" && ':' !in credential.username
            },
          ) { "Credentials do not match the selected security binding" }
        }
      }
    }
  }

  override fun toString(): String = "ClientSettings()"
}
