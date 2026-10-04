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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.util.UUID
import kotlin.reflect.KClass

class URITemplateTest {

  enum class TestEnum {
    @JsonProperty("test-value")
    TestValue,
  }

  @Test
  fun `test encoding`() {
    val path =
      URITemplate("http://example.com/{enum}")
        .resolve(parameters = mapOf("enum" to TestEnum.TestValue))
        .toURI()
        .toString()

    expectThat(path).isEqualTo("http://example.com/test-value")
  }

  @Test
  fun `test enum encoding`() {
    val path =
      URITemplate("http://example.com/{enum}", mapOf("enum" to TestEnum.TestValue))
        .resolve(encoders = PathEncoders.default)
        .toURI()
        .toString()

    expectThat(path).isEqualTo("http://example.com/test-value")
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

    expectThat(path)
      .isEqualTo("http://example.com/objects/${id.toString().replace("-", "")}")
  }

  @ParameterizedTest
  @ValueSource(strings = ["{id}", "{+id}", "{#id}", "{.id}", "{/id}", "{;id}", "{?id}", "{&id}", "{id:3}", "{/id*}"])
  fun `test missing and null variables expand to nothing`(expression: String) {
    val template = URITemplate("https://example.com/items$expression")

    expectThat(template.resolve().toURI().toString()).isEqualTo("https://example.com/items")
    expectThat(template.resolve(parameters = mapOf("id" to null)).toURI().toString())
      .isEqualTo("https://example.com/items")
  }

  @ParameterizedTest
  @CsvSource(
    "'{missing,first,nil,last,missing}', 'one,two'",
    "'{+missing,first,nil,last,missing}', 'one,two'",
    "'{#missing,first,nil,last,missing}', '#one,two'",
    "'{.missing,first,nil,last,missing}', '.one.two'",
    "'{/missing,first,nil,last,missing}', '/one/two'",
    "'{;missing,first,nil,last,missing}', ';first=one;last=two'",
    "'{?missing,first,nil,last,missing}', '?first=one&last=two'",
    "'{&missing,first,nil,last,missing}', '&first=one&last=two'",
  )
  fun `test undefined variables do not add separators`(
    expression: String,
    suffix: String,
  ) {
    val template = URITemplate("https://example.com/items$expression")
    val parameters = mapOf("first" to "one", "nil" to null, "last" to "two")

    expectThat(template.resolve(parameters = parameters).toURI().toString())
      .isEqualTo("https://example.com/items$suffix")
  }

  @ParameterizedTest
  @CsvSource(
    "'{id}', ''",
    "'{+id}', ''",
    "'{#id}', '#'",
    "'{.id}', '.'",
    "'{/id}', '/'",
    "'{;id}', ';id'",
    "'{?id}', '?id='",
    "'{&id}', '&id='",
  )
  fun `test empty strings remain defined`(
    expression: String,
    suffix: String,
  ) {
    val template = URITemplate("https://example.com/items$expression", mapOf("id" to ""))

    expectThat(template.resolve().toURI().toString()).isEqualTo("https://example.com/items$suffix")
  }

  @Test
  fun `test undefined variables preserve literal slashes`() {
    expectThat(URITemplate("https://example.com/items/{id}").resolve().toURI().toString())
      .isEqualTo("https://example.com/items/")
  }

  @Test
  fun `test null overrides template values in base and relative paths`() {
    val template = URITemplate("https://example.com{/version}", mapOf("version" to "v1", "id" to "123"))

    expectThat(template.resolve("/items{/id}").toURI().toString()).isEqualTo("https://example.com/v1/items/123")
    expectThat(template.resolve("/items{/id}", mapOf("version" to null, "id" to null)).toURI().toString())
      .isEqualTo("https://example.com/items")
  }

}
