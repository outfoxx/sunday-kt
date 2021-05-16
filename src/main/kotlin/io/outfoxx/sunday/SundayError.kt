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

package io.outfoxx.sunday

class SundayError(message: String, val reason: Reason? = null, cause: Throwable? = null) :
  RuntimeException(message, cause) {

  enum class Reason(val message: String) {
    UnexpectedEmptyResponse("Unexpected empty reason"),
    InvalidContentType("Invalid Content-Type"),
    NoDecoder("No decoder registered for MediaType"),
    ResponseDecodingFailed("Response decoding failed"),
    EventDecodingFailed("Event decoding failed"),
    InvalidBaseUri("Base URL is invalid after expanding template"),
    NoSupportedContentTypes("None of the provided Content-Types for the request has a registered decoder"),
    NoSupportedAcceptTypes("None of the provided Accept types for the request has a registered decoder")
  }

  constructor(reason: Reason, extraMessage: String? = null, cause: Throwable? = null) :
    this("${reason.message}${extraMessage?.let { " $it" } ?: ""}", reason, cause)
}
