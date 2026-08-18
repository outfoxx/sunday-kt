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

import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.time.Duration

class EventSourceReconnectPolicyTest {

  @Test
  fun `retry delay grows exponentially and respects the maximum`() {
    val retryTime = Duration.ofMillis(500)
    val retryTimeMax = Duration.ofSeconds(15)

    val delays =
      (0..6).map { attempt ->
        EventSource.calculateRetryDelay(attempt, retryTime, retryTimeMax, jitter = 1.0)
      }

    expectThat(delays).isEqualTo(
      listOf(
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(4),
        Duration.ofSeconds(8),
        Duration.ofSeconds(15),
        Duration.ofSeconds(15),
      ),
    )
  }

  @Test
  fun `retry jitter only reduces the exponential delay`() {
    val delay =
      EventSource.calculateRetryDelay(
        retryAttempt = 2,
        retryTime = Duration.ofMillis(500),
        retryTimeMax = Duration.ofSeconds(15),
        jitter = 0.9,
      )

    expectThat(delay).isEqualTo(Duration.ofMillis(1800))
  }

  @Test
  fun `keepalive timeout tolerates missed signals and applies a floor`() {
    expectThat(EventSource.calculateEventTimeout(Duration.ofMillis(500)))
      .isEqualTo(Duration.ofMillis(1500))
    expectThat(EventSource.calculateEventTimeout(Duration.ofMillis(100)))
      .isEqualTo(Duration.ofSeconds(1))
  }
}
