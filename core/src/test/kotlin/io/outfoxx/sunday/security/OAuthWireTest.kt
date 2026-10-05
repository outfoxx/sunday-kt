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

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OAuthWireTest {
  @Test
  fun `specification linked cases`() {
    val corpus = ObjectMapper().readTree(File("../test-fixtures/oauth/cases.json"))
    assertEquals(1, corpus.path("formatVersion").intValue())
    for (case in corpus.path("cases")) {
      val result =
        runCatching {
          val body = case.path("body").textValue()
          when (case.path("kind").textValue()) {
            "discovery" -> OAuthWire.discovery(body)
            "error" -> OAuthWire.error(body)
            "token" ->
              OAuthWire.success(body).tokens(
                case
                  .path("context")
                  .path("scopes")
                  .map { it.textValue() }
                  .toSet(),
                Clock.fixed(Instant.ofEpochMilli(case.path("context").path("clockMillis").longValue()), ZoneOffset.UTC),
              )
            else -> error("Unknown fixture kind")
          }
        }
      assertEquals(case.path("expected").textValue() == "accept", result.isSuccess, case.path("id").textValue())
    }
  }
}
