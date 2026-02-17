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
import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.problems.SundayHttpProblem
import kotlinx.io.Source
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class ProblemsTest {

  @Test
  fun `test forResponse`() {
    val problem =
      SundayHttpProblem.Factory
        .from(TestResponse(400, "Bad Request", listOf(), null))
        .build() as SundayHttpProblem

    expectThat(problem.status).isEqualTo(Status.BadRequest.code)
  }

  @Test
  fun `test forStatus`() {
    val problem = SundayHttpProblem.Factory.from(Status.BadRequest).build() as SundayHttpProblem

    expectThat(problem.status).isEqualTo(Status.BadRequest.code)
  }

  @Test
  fun `test forStatus supports non-standard values`() {
    val problem =
      SundayHttpProblem.Factory.from(Status(195, "AI Thinking")).build() as SundayHttpProblem

    expectThat(problem) {
      get { status }.isEqualTo(195)
      get { title }.isEqualTo("AI Thinking")
    }
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
