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

package io.outfoxx.sunday.jdk

import io.outfoxx.sunday.RequestCancellationException
import io.outfoxx.sunday.http.Headers
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean

internal fun HttpRequest.Builder.headers(headers: Headers) =
  apply {
    headers.forEach { header(it.first, it.second) }
  }

/** Copies every native request setting while optionally omitting headers. Body publishers remain shared. */
fun HttpRequest.copyToBuilder(includeHeaders: Boolean = true): HttpRequest.Builder =
  HttpRequest.newBuilder(this) { _, _ -> includeHeaders }

/** Limits native recovery to one body subscription without changing the caller-owned request. */
internal fun HttpRequest.withBodyRepeatability(repeatable: Boolean): HttpRequest {
  val publisher = bodyPublisher().orElse(null)
  if (repeatable || publisher == null) return this
  val subscribed = AtomicBoolean()
  val guarded =
    object : HttpRequest.BodyPublisher by publisher {
      override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
        if (subscribed.compareAndSet(false, true)) {
          publisher.subscribe(subscriber)
        } else {
          // Let the JDK publisher deliver the failure using its native subscription/error protocol.
          HttpRequest.BodyPublishers
            .ofInputStream {
              throw object :
                IllegalStateException(
                  "Cannot replay a non-repeatable request body",
                ),
                RequestCancellationException {}
            }.subscribe(subscriber)
        }
      }
    }
  return copyToBuilder().method(method(), guarded).build()
}
