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

import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.problems.ProblemFactory
import io.outfoxx.sunday.security.ClientSettings
import okhttp3.OkHttpClient

/** Constructs an application-selected OkHttp transport from prepared settings without acquiring tokens.
 * The application owns the returned transport and its lifecycle.
 */
fun ClientSettings.okhttpTransport(
  problemFactory: ProblemFactory,
  httpClient: OkHttpClient = OkHttpClient(),
): OkHttpTransport =
  OkHttpTransport(
    URITemplate(baseURL.toString()),
    problemFactory,
    httpClient = httpClient,
    tokenManager = tokenManager,
  )
