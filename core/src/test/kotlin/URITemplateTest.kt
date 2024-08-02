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

import com.fasterxml.jackson.annotation.JsonProperty
import io.outfoxx.sunday.PathEncoder
import io.outfoxx.sunday.PathEncoders
import io.outfoxx.sunday.URITemplate
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.KClass

class URITemplateTest {

  enum class TestEnum {
    @JsonProperty("test-value")
    TestValue,
  }

  @Test
  fun `test enum encoding`() {
    val path =
      URITemplate("http://example.com/{enum}", mapOf("enum" to TestEnum.TestValue))
        .resolve(encoders = PathEncoders.default)
        .toURI()
        .toString()

    assertThat(path, equalTo("http://example.com/test-value"))
  }

  @Test
  fun `test custom encoding`() {
    val encoders: Map<KClass<*>, PathEncoder> =
      mapOf(
        UUID::class to { (it as UUID).toString().replace("-", "") },
      )

    val id = UUID.randomUUID()
    val path =
      URITemplate("http://example.com/objects/{id}", mapOf("id" to id, "none" to null))
        .resolve(encoders = encoders)
        .toURI()
        .toString()

    assertThat(
      path,
      equalTo("http://example.com/objects/${id.toString().replace("-", "")}"),
    )
  }

}
