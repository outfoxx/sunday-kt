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

package io.outfoxx.sunday.test

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.Playwright
import io.outfoxx.sunday.security.AuthorizationGrant
import io.outfoxx.sunday.security.AuthorizationRequiredException
import io.outfoxx.sunday.security.OAuthTokenProvider
import io.outfoxx.sunday.security.SecurityBinding
import io.outfoxx.sunday.security.SecurityEndpoints
import io.outfoxx.sunday.security.TokenProvider
import io.outfoxx.sunday.security.TokenRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Runs real HTTP through the selected Sunday transport against a build-owned provider. */
abstract class OAuthInteroperabilityTest {
  abstract fun provider(configuration: OAuthTokenProvider.Configuration): TokenProvider.Refreshing

  private val mapper = ObjectMapper()
  private val mode get() = System.getProperty("sunday.oauth.mode")
  private val base get() = System.getProperty("sunday.oauth.base")
  private val issuer get() = System.getProperty("sunday.oauth.issuer")
  private val callback get() = System.getProperty("sunday.oauth.callback")
  private val verifier = "v".repeat(64)

  @Test
  fun `managed provider acquisition and rotation`() =
    runBlocking {
      for (authentication in OAuthTokenProvider.Authentication.entries) {
        val clientId =
          when (authentication) {
            OAuthTokenProvider.Authentication.None -> "public"
            OAuthTokenProvider.Authentication.ClientSecretBasic -> "basic"
            OAuthTokenProvider.Authentication.ClientSecretPost -> "post"
          }
        if (mode == "replay") replay(clientId, authentication)
        val provider =
          provider(
            OAuthTokenProvider.Configuration(
              identity = "interop",
              clientId = clientId,
              clientSecret = if (authentication == OAuthTokenProvider.Authentication.None) null else "synthetic-secret",
              authentication = authentication,
              issuer = issuer,
              authorization = { authorize(clientId) },
            ),
          )
        val binding =
          SecurityBinding(
            "identity",
            "identity",
            SecurityBinding.Flow.AuthorizationCode,
            endpoints = SecurityEndpoints(discoveryUrl = "$issuer/.well-known/openid-configuration"),
            transport =
              SecurityBinding.CredentialTransport(
                SecurityBinding.CredentialTransport.Location.Header,
                "Authorization",
                "Bearer",
              ),
          )
        val request = TokenRequest(binding, clientId)
        val acquired = provider.acquire(request)
        assertNotNull(acquired.refreshToken)
        val rotated = provider.refresh(request, acquired.refreshToken!!)
        assertNotNull(rotated.refreshToken)
        assertNotEquals(acquired.refreshToken, rotated.refreshToken)
        assertThrows(AuthorizationRequiredException::class.java) {
          runBlocking { provider.refresh(request, acquired.refreshToken) }
        }
      }
    }

  private fun authorize(clientId: String): AuthorizationGrant {
    if (mode == "replay") return AuthorizationGrant("synthetic-code", callback, verifier)
    val challenge =
      Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
      )
    val state = UUID.randomUUID().toString()
    val query =
      mapOf(
        "client_id" to clientId,
        "redirect_uri" to callback,
        "response_type" to "code",
        "scope" to "openid",
        "state" to state,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
      ).entries
        .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, UTF_8)}" }
    Playwright.create().use { playwright ->
      playwright.chromium().launch().use { browser ->
        val page = browser.newPage()
        var response: String? = null
        page.onRequest { request ->
          if (request.url().substringBefore('?') == callback) response = request.url()
        }
        page.navigate("$issuer/protocol/openid-connect/auth?$query")
        page.locator("#username").fill("synthetic-user")
        page.locator("#password").fill("synthetic-password")
        page.locator("#kc-login").click()
        page.waitForCondition { response != null }
        val values =
          URI(response!!).rawQuery.split('&').associate { field ->
            field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), UTF_8)
          }
        check(values["state"] == state)
        return AuthorizationGrant(values.getValue("code"), callback, verifier)
      }
    }
  }

  private fun replay(
    clientId: String,
    authentication: OAuthTokenProvider.Authentication,
  ) {
    admin("DELETE", "/__admin/mappings")
    admin("POST", "/__admin/scenarios/reset")
    val token = URI(issuer).path + "/protocol/openid-connect/token"
    mapping(
      mapOf(
        "request" to mapOf("method" to "GET", "urlPath" to URI(issuer).path + "/.well-known/openid-configuration"),
        "response" to
          mapOf(
            "status" to 200,
            "jsonBody" to
              mapOf(
                "issuer" to issuer,
                "token_endpoint" to "$base$token",
                "authorization_endpoint" to "$issuer/protocol/openid-connect/auth",
                "token_endpoint_auth_methods_supported" to listOf("client_secret_basic", "client_secret_post"),
              ),
          ),
      ),
    )
    listOf("authorization_code", "refresh_token").forEachIndexed { index, grant ->
      val form = mutableMapOf("grant_type" to mapOf("equalTo" to grant))
      if (index == 0) {
        form["code"] = mapOf("equalTo" to "synthetic-code")
        form["code_verifier"] = mapOf("equalTo" to verifier)
        form["redirect_uri"] = mapOf("equalTo" to callback)
      } else {
        form["refresh_token"] = mapOf("equalTo" to "synthetic-refresh-1")
      }
      val headers = mutableMapOf<String, Any>()
      if (authentication == OAuthTokenProvider.Authentication.ClientSecretBasic) {
        headers["Authorization"] =
          mapOf("equalTo" to "Basic " + Base64.getEncoder().encodeToString("$clientId:synthetic-secret".toByteArray()))
      } else {
        form["client_id"] = mapOf("equalTo" to clientId)
        if (authentication ==
          OAuthTokenProvider.Authentication.ClientSecretPost
        ) {
          form["client_secret"] =
            mapOf("equalTo" to "synthetic-secret")
        }
      }
      mapping(
        mapOf(
          "scenarioName" to "rotation",
          "requiredScenarioState" to if (index == 0) "Started" else "acquired",
          "newScenarioState" to if (index == 0) "acquired" else "rotated",
          "request" to mapOf("method" to "POST", "urlPath" to token, "formParameters" to form, "headers" to headers),
          "response" to
            mapOf(
              "status" to 200,
              "jsonBody" to
                mapOf(
                  "access_token" to "synthetic-access-$index",
                  "token_type" to "Bearer",
                  "expires_in" to 60,
                  "refresh_token" to "synthetic-refresh-${index + 1}",
                ),
            ),
        ),
      )
    }
    mapping(
      mapOf(
        "priority" to 10,
        "request" to mapOf("method" to "POST", "urlPath" to token),
        "response" to mapOf("status" to 400, "jsonBody" to mapOf("error" to "invalid_grant")),
      ),
    )
  }

  private fun mapping(value: Map<String, Any>) = admin("POST", "/__admin/mappings", mapper.writeValueAsString(value))

  private fun admin(
    method: String,
    path: String,
    body: String = "",
  ) {
    HttpClient.newHttpClient().use { client ->
      val response =
        client.send(
          HttpRequest
            .newBuilder(URI(base + path))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build(),
          HttpResponse.BodyHandlers.discarding(),
        )
      check(response.statusCode() in 200..299)
    }
  }
}
