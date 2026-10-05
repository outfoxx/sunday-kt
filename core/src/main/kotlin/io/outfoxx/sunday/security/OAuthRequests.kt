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

import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.ClientCredentialsGrant
import com.nimbusds.oauth2.sdk.RefreshTokenGrant
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.oauth2.sdk.token.RefreshToken
import java.net.URI
import com.nimbusds.oauth2.sdk.TokenRequest as NimbusTokenRequest

/** Nimbus owns protocol request construction; Sunday alone executes the request. */
internal object OAuthRequests {
  fun build(
    configuration: OAuthTokenProvider.Configuration,
    binding: SecurityBinding,
    url: URI,
    form: Map<String, String>,
  ): OAuthTokenProvider.ExchangeRequest {
    val grant =
      when (form["grant_type"]) {
        "client_credentials" -> ClientCredentialsGrant()
        "authorization_code" ->
          AuthorizationCodeGrant(
            AuthorizationCode(form.getValue("code")),
            URI(form.getValue("redirect_uri")),
            CodeVerifier(form.getValue("code_verifier")),
          )
        "refresh_token" -> RefreshTokenGrant(RefreshToken(form.getValue("refresh_token")))
        else -> throw TokenProviderException()
      }
    val client = ClientID(configuration.clientId)
    val builder =
      when (configuration.authentication) {
        OAuthTokenProvider.Authentication.None -> NimbusTokenRequest.Builder(url, client, grant)
        OAuthTokenProvider.Authentication.ClientSecretBasic ->
          NimbusTokenRequest.Builder(
            url,
            ClientSecretBasic(client, Secret(configuration.clientSecret!!)),
            grant,
          )
        OAuthTokenProvider.Authentication.ClientSecretPost ->
          NimbusTokenRequest.Builder(
            url,
            ClientSecretPost(client, Secret(configuration.clientSecret!!)),
            grant,
          )
      }
    if (binding.scopes.isNotEmpty()) builder.scope(Scope.parse(binding.scopes.joinToString(" ")))
    val request = builder.build().toHTTPRequest()
    val fields = request.bodyAsFormParameters.mapValues { it.value.single() }.toMutableMap()
    binding.audience?.let { fields["audience"] = it }
    binding.resource?.let { fields["resource"] = it }
    val headers = request.headerMap.mapValues { it.value.single() }.toMutableMap()
    headers["Accept"] = "application/json"
    return OAuthTokenProvider.ExchangeRequest(url, headers, fields)
  }
}
