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

/** Application credentials shared by generated clients; transport modules own OAuth exchange. */
sealed interface Credentials {
  /** Bearer credentials, including managed OAuth acquisition. */
  sealed interface Bearer : Credentials

  /** API-key credentials. */
  sealed interface ApiKey : Credentials

  /** Basic credentials. */
  sealed interface Basic : Credentials

  /** OAuth acquisition credentials. */
  sealed interface OAuth : Bearer
}
