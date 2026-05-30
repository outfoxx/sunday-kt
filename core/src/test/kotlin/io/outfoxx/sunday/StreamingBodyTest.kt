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

import kotlinx.io.readByteArray
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.isEqualTo
import java.nio.file.Files
import java.nio.file.Path

class StreamingBodyTest {

  @Test
  fun `file bodies report length and open replayable sources`(
    @TempDir tempDir: Path,
  ) {
    val path = tempDir.resolve("body.bin")
    Files.write(path, byteArrayOf(1, 2, 3))

    val body = StreamingBody.file(path)

    expectThat(body.contentLength).isEqualTo(3)
    expectThat(body.openSource().readByteArray()).isEqualTo(byteArrayOf(1, 2, 3))
    expectThat(body.openSource().readByteArray()).isEqualTo(byteArrayOf(1, 2, 3))
  }

  @Test
  fun `body length must be non-negative`() {
    expectThrows<IllegalArgumentException> {
      StreamingBody.source(contentLength = -1) {
        error("should not open")
      }
    }
  }
}
