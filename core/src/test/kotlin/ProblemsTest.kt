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

import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.utils.Problems
import kotlinx.io.Source
import org.junit.jupiter.api.Test
import org.zalando.problem.Status
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class ProblemsTest {

  @Test
  fun `test forResponse`() {
    val problem = Problems.forResponse(TestResponse(400, "Bad Request", listOf(), null))

    expectThat(problem.status).isEqualTo(Status.BAD_REQUEST)
  }

  @Test
  fun `test forStatus`() {
    val problem = Problems.forStatus(400, "Bad Request")

    expectThat(problem.status).isEqualTo(Status.BAD_REQUEST)
  }

  @Test
  fun `test forStatus supports non-standard values`() {
    val problem = Problems.forStatus(195, "AI Thinking")

    expectThat(problem.status?.statusCode).isEqualTo(195)
    expectThat(problem.status?.reasonPhrase).isEqualTo("AI Thinking")
  }

  data class TestResponse(
    override val statusCode: Int,
    override val reasonPhrase: String?,
    override val headers: Headers,
    override val body: Source?,
  ) : Response {

    override val trailers: Headers?
      get() = null
    override val request: Request
      get() = TODO("Not yet implemented")
  }

}
