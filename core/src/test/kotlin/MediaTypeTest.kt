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
import io.outfoxx.sunday.MediaType.StandardParameterName.CharSet
import io.outfoxx.sunday.MediaType.Suffix.BER
import io.outfoxx.sunday.MediaType.Suffix.CBOR
import io.outfoxx.sunday.MediaType.Suffix.DER
import io.outfoxx.sunday.MediaType.Suffix.FastInfoSet
import io.outfoxx.sunday.MediaType.Suffix.JSON
import io.outfoxx.sunday.MediaType.Suffix.WBXML
import io.outfoxx.sunday.MediaType.Suffix.XML
import io.outfoxx.sunday.MediaType.Suffix.Zip
import io.outfoxx.sunday.MediaType.Tree.Obsolete
import io.outfoxx.sunday.MediaType.Tree.Personal
import io.outfoxx.sunday.MediaType.Tree.Standard
import io.outfoxx.sunday.MediaType.Tree.Unregistered
import io.outfoxx.sunday.MediaType.Tree.Vendor
import io.outfoxx.sunday.MediaType.Type.Any
import io.outfoxx.sunday.MediaType.Type.Application
import io.outfoxx.sunday.MediaType.Type.Audio
import io.outfoxx.sunday.MediaType.Type.Example
import io.outfoxx.sunday.MediaType.Type.Font
import io.outfoxx.sunday.MediaType.Type.Image
import io.outfoxx.sunday.MediaType.Type.Message
import io.outfoxx.sunday.MediaType.Type.Model
import io.outfoxx.sunday.MediaType.Type.Multipart
import io.outfoxx.sunday.MediaType.Type.Text
import io.outfoxx.sunday.MediaType.Type.Video
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaTypeTest {

  @Test
  fun `extracts from headers`() {

    val mediaTypes = MediaType.from(
      listOf(
        "${MediaType.JSON.value} , ${MediaType.CBOR.value}",
        MediaType.HTML.value
      )
    )

    assertThat(mediaTypes, containsInAnyOrder(MediaType.JSON, MediaType.CBOR, MediaType.HTML))
  }

  @Test
  fun `test equality`() {

    val mediaType = MediaType.HTML.with(CharSet, "utf-8")
    assertEquals(mediaType, mediaType)

    assertEquals(
      MediaType.HTML.with(CharSet, "utf-8").with("test", "123"),
      MediaType.HTML.with(CharSet, "utf-8").with("test", "123"),
    )

    assertNotEquals(
      MediaType.from("application/text"),
      MediaType.from("text/json"),
    )

    assertNotEquals(
      MediaType.from("application/x-html"),
      MediaType.from("application/x.html"),
    )

    assertNotEquals(
      MediaType.from("text/html"),
      MediaType.from("text/json"),
    )

    assertNotEquals(
      MediaType.from("application/problem+json"),
      MediaType.from("application/problem+cbor"),
    )

    assertNotEquals(
      MediaType.HTML.with("a", "123").with("b", "456"),
      MediaType.HTML.with("a", "123").with("b", "789"),
    )

  }

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

    assertThat(MediaType.from("application/*").type, equalTo(Application))
    assertThat(MediaType.from("audio/*").type, equalTo(Audio))
    assertThat(MediaType.from("example/*").type, equalTo(Example))
    assertThat(MediaType.from("font/*").type, equalTo(Font))
    assertThat(MediaType.from("image/*").type, equalTo(Image))
    assertThat(MediaType.from("message/*").type, equalTo(Message))
    assertThat(MediaType.from("model/*").type, equalTo(Model))
    assertThat(MediaType.from("multipart/*").type, equalTo(Multipart))
    assertThat(MediaType.from("text/*").type, equalTo(Text))
    assertThat(MediaType.from("video/*").type, equalTo(Video))
    assertThat(MediaType.from("*/*").type, equalTo(Any))

    assertThat(MediaType.from("application/test").tree, equalTo(Standard))
    assertThat(MediaType.from("application/vnd.test").tree, equalTo(Vendor))
    assertThat(MediaType.from("application/prs.test").tree, equalTo(Personal))
    assertThat(MediaType.from("application/x.test").tree, equalTo(Unregistered))
    assertThat(MediaType.from("application/x-test").tree, equalTo(Obsolete))

    assertThat(MediaType.from("application/text+xml").suffix, equalTo(XML))
    assertThat(MediaType.from("application/text+json").suffix, equalTo(JSON))
    assertThat(MediaType.from("application/text+ber").suffix, equalTo(BER))
    assertThat(MediaType.from("application/text+der").suffix, equalTo(DER))
    assertThat(MediaType.from("application/text+fastinfoset").suffix, equalTo(FastInfoSet))
    assertThat(MediaType.from("application/text+wbxml").suffix, equalTo(WBXML))
    assertThat(MediaType.from("application/text+zip").suffix, equalTo(Zip))
    assertThat(MediaType.from("application/text+cbor").suffix, equalTo(CBOR))
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
  fun `test parameter access`() {

    val mediaType =
      MediaType.HTML.with(CharSet, "utf-8").with("test", "123")

    assertEquals(mediaType.parameter(CharSet), "utf-8")
    assertEquals(mediaType.parameter("test"), "123")
    assertNull(mediaType.parameter("none"))
  }

  @Test
  fun `test parameter override`() {

    assertEquals(
      MediaType.HTML
        .with("test", "123")
        .with("test", "456")
        .parameter("test"),
      "456"
    )
    assertEquals(
      MediaType.HTML
        .with("test", "456")
        .with("test", "123")
        .parameter("test"),
      "123"
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
