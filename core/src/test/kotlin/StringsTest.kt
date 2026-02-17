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

import io.outfoxx.sunday.utils.buffer
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class StringsTest {

  @Test
  fun `buffer writes string with charset`() {
    val input = "hello"

    val buffer = input.buffer(Charsets.UTF_16)

    expectThat(buffer.readByteArray()).isEqualTo(input.toByteArray(Charsets.UTF_16))
  }
}
