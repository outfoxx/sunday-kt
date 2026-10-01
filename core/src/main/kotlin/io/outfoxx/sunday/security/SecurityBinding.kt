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

/** One logical scheme in the complete alternative selected for an operation. */
data class SecurityBinding(
  val scheme: String,
  val provider: String,
  val flow: Flow = Flow.External,
  val profile: String? = null,
  val scopes: Set<String> = emptySet(),
  val endpoints: SecurityEndpoints = SecurityEndpoints(),
  val audience: String? = null,
  val resource: String? = null,
  val transport: CredentialTransport,
) {
  /** Supported acquisition contracts; interactive authorization remains application-owned. */
  enum class Flow { ClientCredentials, AuthorizationCode, External, Static }

  /** Wire location for a provider's credential. */
  data class CredentialTransport(
    val location: Location,
    val name: String,
    val prefix: String? = null,
  ) {
    /** Supported HTTP credential locations. */
    enum class Location { Header, Query, Cookie }
  }
}
