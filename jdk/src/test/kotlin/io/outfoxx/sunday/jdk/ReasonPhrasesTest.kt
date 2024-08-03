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

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test

class ReasonPhrasesTest {

  @Test
  fun `test lookups`() {
    assertThat(ReasonPhrases.lookup(99), `is`(nullValue()))
    assertThat(ReasonPhrases.lookup(100), equalTo("Continue"))
    assertThat(ReasonPhrases.lookup(200), equalTo("OK"))
    assertThat(ReasonPhrases.lookup(300), equalTo("Multiple Choices"))
    assertThat(ReasonPhrases.lookup(400), equalTo("Bad Request"))
    assertThat(ReasonPhrases.lookup(500), equalTo("Server Error"))
    assertThat(ReasonPhrases.lookup(600), `is`(nullValue()))
  }

}
