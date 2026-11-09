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

package io.outfoxx.sunday.okhttp

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient

/** Shares lazy native-client configurations across a transport's managed calls. */
internal class OkHttpAttemptClients(
  private val client: OkHttpClient,
) {
  val standard: OkHttpClient by lazy {
    client
      .newBuilder()
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .build()
  }

  val credentialed: OkHttpClient by lazy {
    standard
      .newBuilder()
      .authenticator(Authenticator.NONE)
      .cookieJar(CookieJar.NO_COOKIES)
      .build()
  }
}
