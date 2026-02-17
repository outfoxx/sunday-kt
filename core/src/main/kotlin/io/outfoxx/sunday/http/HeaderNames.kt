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

package io.outfoxx.sunday.http

/**
 * Commonly used HTTP header names.
 */
object HeaderNames {
  const val ACCEPT = "Accept"
  const val AUTHORIZATION = "Authorization"
  const val CONNECTION = "Connection"
  const val CONTENT_LENGTH = "Content-Length"
  const val CONTENT_TYPE = "Content-Type"
  const val LOCATION = "Location"
  const val SERVER = "Server"
  const val TRANSFER_ENCODING = "Transfer-Encoding"
  const val USER_AGENT = "User-Agent"
  const val COOKIE = "Cookie"
  const val SET_COOKIE = "Set-Cookie"
  const val EXPECT = "Expect"
  const val LAST_EVENT_ID = "Last-Event-Id"
}
