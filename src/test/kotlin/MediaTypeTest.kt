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

import io.outfoxx.sunday.MediaType
import io.outfoxx.sunday.MediaType.Suffix.JSON
import io.outfoxx.sunday.MediaType.Suffix.XML
import io.outfoxx.sunday.MediaType.Tree.Obsolete
import io.outfoxx.sunday.MediaType.Tree.Personal
import io.outfoxx.sunday.MediaType.Tree.Standard
import io.outfoxx.sunday.MediaType.Tree.Vendor
import io.outfoxx.sunday.MediaType.Type.Any
import io.outfoxx.sunday.MediaType.Type.Application
import io.outfoxx.sunday.MediaType.Type.Image
import io.outfoxx.sunday.MediaType.Type.Text
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaTypeTest {

  @Test
  fun `test compatibility`() {
    assertTrue(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "plain",
          JSON,
          mapOf("a" to "b")
        )
      )
    ) { "Test compatibility" }

    assertFalse(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Image,
          Vendor,
          "plain",
          JSON,
          mapOf("a" to "b")
        )
      )
    ) { "Test incompatibility in types" }

    assertFalse(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Personal,
          "plain",
          JSON,
          mapOf("a" to "b")
        )
      )
    ) { "Test incompatibility in trees" }

    assertFalse(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "html",
          JSON,
          mapOf("a" to "b")
        )
      )
    ) { "Test incompatibility in subtypes" }

    assertFalse(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "plain",
          XML,
          mapOf("a" to "b")
        )
      )
    ) { "Test incompatibility in suffixes" }

    assertFalse(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "plain",
          JSON,
          mapOf("a" to "c")
        )
      )
    ) { "Test incompatibility in parameter values" }

    assertFalse(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "plain",
          parameters = mapOf("a" to "c")
        )
      )
    ) { "Test incompatibility in parameter values missing suffix" }

    assertTrue(
      MediaType(Text, subtype = "html", parameters = mapOf("custom-charset" to "utf-8")).compatible(
        MediaType(
          Text,
          subtype = "html",
          parameters = mapOf(
            "charset" to "utf-8"
          )
        )
      )
    ) { "Test compatibility with different parameters" }

    assertTrue(
      MediaType(Text, subtype = "html", parameters = mapOf("charset" to "utf-8")).compatible(
        MediaType(
          Text,
          subtype = "html",
          parameters = mapOf(
            "CHARSET" to "UTF-8"
          )
        )
      )
    ) { "Test compatibility with different parameter cases" }

    assertTrue(
      MediaType(Text, subtype = "html", parameters = mapOf("charset" to "utf-8")).compatible(
        MediaType(
          Text,
          subtype = "html",
          parameters = mapOf(
            "test" to "it"
          )
        )
      )
    ) { "Test compatibility with different parameters" }

    assertFalse(
      MediaType(Text, subtype = "html", parameters = mapOf("charset" to "utf-8")).compatible(
        MediaType(
          Text,
          subtype = "html",
          parameters = mapOf(
            "charset" to "utf-16"
          )
        )
      )
    ) { "Test compatibility with different parameter values" }

    assertTrue(
      MediaType(Text, subtype = "html").compatible(
        MediaType(
          Any,
          subtype = "*"
        )
      )
    ) { "Test compatibility with wildcard type & subtype" }

    assertTrue(
      MediaType(Text, subtype = "html").compatible(
        MediaType(
          Any,
          subtype = "html"
        )
      )
    ) { "Test compatibility with wildcard type" }

    assertTrue(
      MediaType(Text, subtype = "html").compatible(
        MediaType(
          Text,
          subtype = "*"
        )
      )
    ) { "Test compatibility with wildcard subtype" }
  }

  @Test
  fun `test parse`() {
    assertThat(
      "Test parsing",
      MediaType(Application, Standard, "problem", JSON, mapOf("charset" to "utf-8")),
      equalTo(MediaType.from("application/problem+json;charset=utf-8"))
    )

    assertThat(
      "Test parsing with non-standard tree",
      MediaType(Application, Obsolete, "www-form-urlencoded"),
      equalTo(MediaType.from("application/x-www-form-urlencoded"))
    )

    assertThat(
      "Test parsing with non-standard tree and complexs subtype",
      MediaType(Application, Obsolete, "x509-ca-cert"),
      equalTo(MediaType.from("application/x-x509-ca-cert"))
    )

    assertThat(
      "Test parsing with multiple parameters",
      MediaType(
        Application,
        Vendor,
        "yaml",
        parameters = mapOf("charset" to "utf-8", "something" to "else")
      ),
      equalTo(MediaType.from("application/vnd.yaml;charset=utf-8;something=else"))
    )

    assertThat(
      "Test parsing with different cases",
      MediaType(
        Application,
        Vendor,
        "yaml",
        parameters = mapOf("charset" to "utf-8", "something" to "else")
      ),
      equalTo(MediaType.from("APPLICATION/VND.YAML;CHARSET=UTF-8;SOMETHING=ELSE"))
    )

    assertThat(
      "Test parsing with different random spacing",
      MediaType(
        Application,
        Vendor,
        "yaml",
        parameters = mapOf("charset" to "utf-8", "something" to "else")
      ),
      equalTo(MediaType.from("APPLICATION/VND.YAML  ;  CHARSET=UTF-8 ; SOMETHING=ELSE   "))
    )
  }

  @Test
  fun `test value`() {
    assertEquals(
      MediaType(
        Application,
        Vendor,
        "yaml",
        parameters = mapOf("charset" to "utf-8", "something" to "else"),
      ).value,
      "application/vnd.yaml;charset=utf-8;something=else"
    )
  }

  @Test
  fun `test sanity`() {
    val jsonWithCharset = MediaType.JSON.with(parameters = mapOf("charset" to "utf-8"))

    assertTrue(jsonWithCharset.compatible(MediaType.JSON))
    assertFalse(jsonWithCharset.compatible(MediaType.JSONStructured))
    assertFalse(jsonWithCharset.compatible(MediaType.HTML))
    assertTrue(jsonWithCharset.compatible(MediaType.Any))

    val htmlWithCharset = MediaType.HTML.with(parameters = mapOf("charset" to "utf-8"))

    assertTrue(htmlWithCharset.compatible(MediaType.HTML))
    assertFalse(htmlWithCharset.compatible(MediaType.JSON))
    assertFalse(htmlWithCharset.compatible(MediaType.JSONStructured))
    assertTrue(htmlWithCharset.compatible(MediaType.Any))
  }
}