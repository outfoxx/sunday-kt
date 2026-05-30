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

import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A replayable streaming request body.
 */
class StreamingBody private constructor(
  val contentLength: Long?,
  private val sourceFactory: () -> Source,
) {

  init {
    require(contentLength == null || contentLength >= 0) {
      "contentLength must be greater than or equal to zero"
    }
  }

  /**
   * Opens a fresh body source.
   */
  fun openSource(): Source = sourceFactory()

  companion object {

    /**
     * Creates a streaming body from a replayable source factory.
     */
    fun source(
      contentLength: Long? = null,
      sourceFactory: () -> Source,
    ): StreamingBody = StreamingBody(contentLength, sourceFactory)

    /**
     * Creates a streaming body from a replayable input stream factory.
     */
    fun inputStream(
      contentLength: Long? = null,
      inputStreamFactory: () -> InputStream,
    ): StreamingBody =
      source(contentLength) {
        inputStreamFactory()
          .asSource()
          .buffered()
      }

    /**
     * Creates a streaming body from a file.
     */
    fun file(path: Path): StreamingBody =
      inputStream(Files.size(path)) {
        Files.newInputStream(path)
      }
  }
}
