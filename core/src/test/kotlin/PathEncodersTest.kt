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

import io.outfoxx.sunday.PathEncoders
import io.outfoxx.sunday.add
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import java.util.UUID

class PathEncodersTest {

  @Test
  fun `adding implicitly typed encoders`() {
    val encoders = PathEncoders.default.add(UUID::toString)
    assertThat(encoders, Matchers.aMapWithSize(2))
  }

  @Test
  fun `adding explicitly typed encoders`() {
    val encoders = PathEncoders.default.add(UUID::class, UUID::toString)
    assertThat(encoders, Matchers.aMapWithSize(2))
  }

}
