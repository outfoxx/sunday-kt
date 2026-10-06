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

import com.nimbusds.oauth2.sdk.AccessTokenResponse
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.`as`.AuthorizationServerMetadata
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pins the observed normalization that prevents adopting Nimbus response parsing. */
class NimbusEvaluationTest {
  private fun token(fields: String) =
    AccessTokenResponse
      .parse(
        JSONObjectUtils.parse("""{"access_token":"synthetic","token_type":"Bearer"$fields}"""),
      ).toJSONObject()

  @Test
  fun `expiry coercion cannot enforce the wire contract`() {
    assertFalse(token("").containsKey("expires_in"))
    assertEquals(1L, (token(",\"expires_in\":1.5")["expires_in"] as Number).toLong())
    assertEquals(60L, (token(",\"expires_in\":\"60\"")["expires_in"] as Number).toLong())
    assertThrows(ParseException::class.java) { token(",\"expires_in\":null") }
  }

  @Test
  fun `null optional values are accepted and discovery loses presence information`() {
    for (field in listOf("refresh_token", "scope")) {
      val parsed = token(",\"$field\":null")
      assertTrue(parsed.containsKey(field))
      assertNull(parsed[field])
    }
    val metadata =
      AuthorizationServerMetadata.parse(
        """{"issuer":"https://issuer.example","token_endpoint_auth_methods_supported":null}""",
      )
    assertFalse(metadata.toJSONObject().containsKey("token_endpoint_auth_methods_supported"))
    assertThrows(ParseException::class.java) {
      AuthorizationServerMetadata.parse(
        """{"issuer":"https://issuer.example","token_endpoint_auth_methods_supported":["client_secret_basic",42]}""",
      )
    }
  }
}
