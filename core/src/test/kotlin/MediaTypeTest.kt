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
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNotEqualTo
import strikt.assertions.isNull
import strikt.assertions.isTrue

class MediaTypeTest {

  @Test
  fun `extracts from headers`() {
    val mediaTypes =
      MediaType.from(
        listOf(
          "${MediaType.JSON.value} , ${MediaType.CBOR.value}",
          MediaType.HTML.value,
        ),
      )

    expectThat(mediaTypes)
      .containsExactlyInAnyOrder(MediaType.JSON, MediaType.CBOR, MediaType.HTML)
  }

  @Test
  fun `test equality`() {
    val mediaType = MediaType.HTML.with(CharSet, "utf-8")
    expectThat(mediaType).isEqualTo(mediaType)

    expectThat(
      MediaType.HTML.with(CharSet, "utf-8").with("test", "123"),
    ).isEqualTo(MediaType.HTML.with(CharSet, "utf-8").with("test", "123"))

    expectThat(
      MediaType.from("application/text"),
    ).isNotEqualTo(MediaType.from("text/json"))

    expectThat(
      MediaType.from("application/x-html"),
    ).isNotEqualTo(MediaType.from("application/x.html"))

    expectThat(
      MediaType.from("text/html"),
    ).isNotEqualTo(MediaType.from("text/json"))

    expectThat(
      MediaType.from("application/problem+json"),
    ).isNotEqualTo(MediaType.from("application/problem+cbor"))

    expectThat(
      MediaType.HTML.with("a", "123").with("b", "456"),
    ).isNotEqualTo(MediaType.HTML.with("a", "123").with("b", "789"))

  }

  @Test
  @Suppress("LongMethod")
  fun `test compatibility`() {
    expectThat(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "plain",
          JSON,
          mapOf("a" to "b"),
        ),
      ),
    ).isTrue()

    expectThat(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Image,
          Vendor,
          "plain",
          JSON,
          mapOf("a" to "b"),
        ),
      ),
    ).isFalse()

    expectThat(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Personal,
          "plain",
          JSON,
          mapOf("a" to "b"),
        ),
      ),
    ).isFalse()

    expectThat(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "html",
          JSON,
          mapOf("a" to "b"),
        ),
      ),
    ).isFalse()

    expectThat(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "plain",
          XML,
          mapOf("a" to "b"),
        ),
      ),
    ).isFalse()

    expectThat(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "plain",
          JSON,
          mapOf("a" to "c"),
        ),
      ),
    ).isFalse()

    expectThat(
      MediaType(Text, Vendor, "plain", JSON, mapOf("a" to "b")).compatible(
        MediaType(
          Text,
          Vendor,
          "plain",
          parameters = mapOf("a" to "c"),
        ),
      ),
    ).isFalse()

    expectThat(
      MediaType(Text, subtype = "html", parameters = mapOf("custom-charset" to "utf-8")).compatible(
        MediaType(
          Text,
          subtype = "html",
          parameters =
            mapOf(
              "charset" to "utf-8",
            ),
        ),
      ),
    ).isTrue()

    expectThat(
      MediaType(Text, subtype = "html", parameters = mapOf("charset" to "utf-8")).compatible(
        MediaType(
          Text,
          subtype = "html",
          parameters =
            mapOf(
              "CHARSET" to "UTF-8",
            ),
        ),
      ),
    ).isTrue()

    expectThat(
      MediaType(Text, subtype = "html", parameters = mapOf("charset" to "utf-8")).compatible(
        MediaType(
          Text,
          subtype = "html",
          parameters =
            mapOf(
              "test" to "it",
            ),
        ),
      ),
    ).isTrue()

    expectThat(
      MediaType(Text, subtype = "html", parameters = mapOf("charset" to "utf-8")).compatible(
        MediaType(
          Text,
          subtype = "html",
          parameters =
            mapOf(
              "charset" to "utf-16",
            ),
        ),
      ),
    ).isFalse()

    expectThat(
      MediaType(Text, subtype = "html").compatible(
        MediaType(
          Any,
          subtype = "*",
        ),
      ),
    ).isTrue()

    expectThat(
      MediaType(Text, subtype = "html").compatible(
        MediaType(
          Any,
          subtype = "html",
        ),
      ),
    ).isTrue()

    expectThat(
      MediaType(Text, subtype = "html").compatible(
        MediaType(
          Text,
          subtype = "*",
        ),
      ),
    ).isTrue()
  }

  @Test
  @Suppress("LongMethod")
  fun `test parse`() {
    expectThat(MediaType(Application, Standard, "problem", JSON, mapOf("charset" to "utf-8")))
      .isEqualTo(MediaType.from("application/problem+json;charset=utf-8"))

    expectThat(MediaType(Application, Obsolete, "www-form-urlencoded"))
      .isEqualTo(MediaType.from("application/x-www-form-urlencoded"))

    expectThat(MediaType(Application, Obsolete, "x509-ca-cert"))
      .isEqualTo(MediaType.from("application/x-x509-ca-cert"))

    expectThat(
      MediaType(
        Application,
        Vendor,
        "yaml",
        parameters = mapOf("charset" to "utf-8", "something" to "else"),
      ),
    ).isEqualTo(MediaType.from("application/vnd.yaml;charset=utf-8;something=else"))

    expectThat(
      MediaType(
        Application,
        Vendor,
        "yaml",
        parameters = mapOf("charset" to "utf-8", "something" to "else"),
      ),
    ).isEqualTo(MediaType.from("APPLICATION/VND.YAML;CHARSET=UTF-8;SOMETHING=ELSE"))

    expectThat(
      MediaType(
        Application,
        Vendor,
        "yaml",
        parameters = mapOf("charset" to "utf-8", "something" to "else"),
      ),
    ).isEqualTo(MediaType.from("APPLICATION/VND.YAML  ;  CHARSET=UTF-8 ; SOMETHING=ELSE   "))

    expectThat(MediaType.from("application/*").type).isEqualTo(Application)
    expectThat(MediaType.from("audio/*").type).isEqualTo(Audio)
    expectThat(MediaType.from("example/*").type).isEqualTo(Example)
    expectThat(MediaType.from("font/*").type).isEqualTo(Font)
    expectThat(MediaType.from("image/*").type).isEqualTo(Image)
    expectThat(MediaType.from("message/*").type).isEqualTo(Message)
    expectThat(MediaType.from("model/*").type).isEqualTo(Model)
    expectThat(MediaType.from("multipart/*").type).isEqualTo(Multipart)
    expectThat(MediaType.from("text/*").type).isEqualTo(Text)
    expectThat(MediaType.from("video/*").type).isEqualTo(Video)
    expectThat(MediaType.from("*/*").type).isEqualTo(Any)

    expectThat(MediaType.from("application/test").tree).isEqualTo(Standard)
    expectThat(MediaType.from("application/vnd.test").tree).isEqualTo(Vendor)
    expectThat(MediaType.from("application/prs.test").tree).isEqualTo(Personal)
    expectThat(MediaType.from("application/x.test").tree).isEqualTo(Unregistered)
    expectThat(MediaType.from("application/x-test").tree).isEqualTo(Obsolete)

    expectThat(MediaType.from("application/text+xml").suffix).isEqualTo(XML)
    expectThat(MediaType.from("application/text+json").suffix).isEqualTo(JSON)
    expectThat(MediaType.from("application/text+ber").suffix).isEqualTo(BER)
    expectThat(MediaType.from("application/text+der").suffix).isEqualTo(DER)
    expectThat(MediaType.from("application/text+fastinfoset").suffix).isEqualTo(FastInfoSet)
    expectThat(MediaType.from("application/text+wbxml").suffix).isEqualTo(WBXML)
    expectThat(MediaType.from("application/text+zip").suffix).isEqualTo(Zip)
    expectThat(MediaType.from("application/text+cbor").suffix).isEqualTo(CBOR)
  }

  @Test
  fun `test value`() {
    expectThat(
      MediaType(
        Application,
        Vendor,
        "yaml",
        parameters = mapOf("charset" to "utf-8", "something" to "else"),
      ).value,
    ).isEqualTo("application/vnd.yaml;charset=utf-8;something=else")
  }

  @Test
  fun `test parameter access`() {
    val mediaType =
      MediaType.HTML.with(CharSet, "utf-8").with("test", "123")

    expectThat(mediaType.parameter(CharSet)).isEqualTo("utf-8")
    expectThat(mediaType.parameter("test")).isEqualTo("123")
    expectThat(mediaType.parameter("none")).isNull()
  }

  @Test
  fun `test parameter override`() {
    expectThat(
      MediaType.HTML
        .with("test", "123")
        .with("test", "456")
        .parameter("test"),
    ).isEqualTo("456")
    expectThat(
      MediaType.HTML
        .with("test", "456")
        .with("test", "123")
        .parameter("test"),
    ).isEqualTo("123")
  }

  @Test
  fun `test sanity`() {
    val jsonWithCharset = MediaType.JSON.with(parameters = mapOf("charset" to "utf-8"))

    expectThat(jsonWithCharset.compatible(MediaType.JSON)).isTrue()
    expectThat(jsonWithCharset.compatible(MediaType.JSONStructured)).isFalse()
    expectThat(jsonWithCharset.compatible(MediaType.HTML)).isFalse()
    expectThat(jsonWithCharset.compatible(MediaType.Any)).isTrue()

    val htmlWithCharset = MediaType.HTML.with(parameters = mapOf("charset" to "utf-8"))

    expectThat(htmlWithCharset.compatible(MediaType.HTML)).isTrue()
    expectThat(htmlWithCharset.compatible(MediaType.JSON)).isFalse()
    expectThat(htmlWithCharset.compatible(MediaType.JSONStructured)).isFalse()
    expectThat(htmlWithCharset.compatible(MediaType.Any)).isTrue()
  }

  @Test
  fun `test constructor`() {
    val mediaType = MediaType(Application, Vendor, "test", Zip, "charset" to "utf-8")

    expectThat(mediaType.value).isEqualTo("application/vnd.test+zip;charset=utf-8")
  }
}
