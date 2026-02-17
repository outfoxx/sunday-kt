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

import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull

class ReasonPhrasesTest {

  @Test
  fun `test lookups`() {
    expectThat(ReasonPhrases.lookup(99)).isNull()
    expectThat(ReasonPhrases.lookup(100)).isEqualTo("Continue")
    expectThat(ReasonPhrases.lookup(200)).isEqualTo("OK")
    expectThat(ReasonPhrases.lookup(300)).isEqualTo("Multiple Choices")
    expectThat(ReasonPhrases.lookup(400)).isEqualTo("Bad Request")
    expectThat(ReasonPhrases.lookup(500)).isEqualTo("Server Error")
    expectThat(ReasonPhrases.lookup(600)).isNull()
  }

}
