/*
 * Copyright 2026 Outfox, Inc.
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

/** Recognizes invalid-token challenges without confusing quoted parameters or other authentication schemes. */
object BearerChallenge {
  /** Returns whether a complete Bearer challenge explicitly reports an invalid access token. */
  fun isInvalidToken(header: String): Boolean {
    val parts = mutableListOf<String>()
    var start = 0
    var quoted = false
    var escaped = false
    header.forEachIndexed { index, character ->
      when {
        escaped -> escaped = false
        quoted && character == '\\' -> escaped = true
        character == '"' -> quoted = !quoted
        !quoted && character == ',' -> {
          parts += header.substring(start, index).trim()
          start = index + 1
        }
      }
    }
    if (quoted || escaped) return false
    parts += header.substring(start).trim()
    var bearer = false
    parts.forEach { entry ->
      var part = entry
      val challenge = Regex("([a-z][a-z0-9_-]*)\\s+(?!\\s*=)(.*)", RegexOption.IGNORE_CASE).matchEntire(part)
      if (challenge != null) {
        bearer = challenge.groupValues[1].equals("bearer", true)
        part = challenge.groupValues[2]
      } else if (part.matches(Regex("[a-z][a-z0-9_-]*", RegexOption.IGNORE_CASE))) {
        bearer = false
      }
      if (bearer && part.matches(Regex("(?i:error)\\s*=\\s*(?:\"invalid_token\"|invalid_token)"))) return true
    }
    return false
  }
}
