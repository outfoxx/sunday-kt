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

/** True when ambient client credentials and automatic redirects must not bypass credential ownership. */
val Request<*>.isCredentialed: Boolean
  get() =
    securityContext != null ||
      headers.any {
        it.first.equals("Authorization", true) || it.first.equals("Cookie", true)
      }

/** Builds an HTTP redirect from the prepared request after response adaptation. */
fun <TReq> Request<TReq>.redirectedRequest(
  response: Response<*>,
  sent: Request<*>,
): Request<TReq>? {
  if (sent.isCredentialed || response.statusCode !in setOf(301, 302, 303, 307, 308)) return null
  val location = response.headers.firstOrNull { it.first.equals("Location", true) }?.second ?: return null
  val target = runCatching { sent.uri.resolve(location) }.getOrNull() ?: return null
  if (target.scheme !in setOf("http", "https")) return null
  val get =
    (response.statusCode == 303 && sent.method != Method.Head) ||
      (response.statusCode in setOf(301, 302) && sent.method == Method.Post)
  if (!get && !sent.isBodyRepeatable) return null
  val prepared = if (get) withBody(null) else this
  return prepared.copy(
    uri = target,
    method = if (get) Method.Get else sent.method,
    headers =
      if (get) {
        prepared.headers.filterNot {
          it.first.lowercase() in setOf("content-type", "content-length", "transfer-encoding")
        }
      } else {
        prepared.headers
      },
  )
}
