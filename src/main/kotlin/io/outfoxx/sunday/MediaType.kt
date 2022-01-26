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

import java.util.Locale.ENGLISH
import kotlin.text.RegexOption.IGNORE_CASE

class MediaType(
  val type: Type,
  val tree: Tree = Tree.Standard,
  subtype: String = "*",
  val suffix: Suffix? = null,
  parameters: Map<String, String> = emptyMap()
) {

  constructor(
    type: Type,
    tree: Tree = Tree.Standard,
    subtype: String = "*",
    suffix: Suffix? = null,
    vararg parameters: Pair<String, String>
  ) : this(type, tree, subtype, suffix, mapOf(*parameters))

  enum class StandardParameterName(val code: String) {
    CharSet("charset")
  }

  enum class Type(val code: String) {
    Application("application"),
    Audio("audio"),
    Example("example"),
    Font("font"),
    Image("image"),
    Message("message"),
    Model("model"),
    Multipart("multipart"),
    Text("text"),
    Video("video"),
    Any("*");

    companion object {

      fun fromCode(code: String) = values().firstOrNull { it.code == code }
    }
  }

  enum class Tree(val code: String) {
    Standard(""),
    Vendor("vnd."),
    Personal("prs."),
    Unregistered("x."),
    Obsolete("x-"),
    Any("*");

    companion object {

      fun fromCode(code: String) = values().firstOrNull { it.code == code }
    }
  }

  enum class Suffix(val code: String) {
    XML("xml"),
    JSON("json"),
    BER("ber"),
    DER("der"),
    FastInfoSet("fastinfoset"),
    WBXML("wbxml"),
    Zip("zip"),
    CBOR("cbor");

    companion object {

      fun fromCode(code: String) = values().firstOrNull { it.code == code }
    }
  }

  val subtype = subtype.lowercase(ENGLISH)
  val parameters =
    parameters
      .map { it.key.lowercase(ENGLISH) to it.value.lowercase(ENGLISH) }
      .toMap()

  fun parameter(name: StandardParameterName): String? {
    return parameters[name.code]
  }

  fun parameter(name: String): String? {
    return parameters[name]
  }

  fun with(
    type: Type? = null,
    tree: Tree? = null,
    subtype: String? = null,
    parameters: Map<String, String>? = null
  ) =
    MediaType(
      type ?: this.type,
      tree ?: this.tree,
      subtype ?: this.subtype,
      suffix,
      parameters ?: this.parameters
    )

  fun with(parameter: StandardParameterName, value: String) =
    with(parameters = parameters + mapOf(parameter.code to value))

  fun with(parameter: String, value: String) =
    with(parameters = parameters + mapOf(parameter to value))

  val value: String
    get() {
      val type = this.type.code
      val tree = this.tree.code
      val suffix = this.suffix?.let { "+${it.name.lowercase(ENGLISH)}" } ?: ""
      val parameters = this.parameters.keys.sorted().joinToString("") { ";$it=${parameters[it]}" }
      return "$type/$tree$subtype$suffix$parameters"
    }

  fun compatible(other: MediaType): Boolean {
    return when {
      this.type != Type.Any && other.type != Type.Any && this.type != other.type -> false
      this.tree != Tree.Any && other.tree != Tree.Any && this.tree != other.tree -> false
      this.subtype != "*" && other.subtype != "*" && this.subtype != other.subtype -> false
      this.suffix != other.suffix -> false
      else ->
        this.parameters.keys.intersect(other.parameters.keys)
          .all { this.parameters[it] == other.parameters[it] }
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as MediaType

    if (type != other.type) return false
    if (tree != other.tree) return false
    if (suffix != other.suffix) return false
    if (subtype != other.subtype) return false
    if (parameters != other.parameters) return false

    return true
  }

  override fun hashCode(): Int {
    var result = type.hashCode()
    result = 31 * result + tree.hashCode()
    result = 31 * result + (suffix?.hashCode() ?: 0)
    result = 31 * result + subtype.hashCode()
    result = 31 * result + parameters.hashCode()
    return result
  }

  override fun toString() = value

  companion object {

    fun from(acceptHeaders: List<String>): List<MediaType> {
      return acceptHeaders.flatMap { header -> header.split(",") }.mapNotNull { from(it.trim()) }
    }

    fun from(string: String): MediaType? {
      val match = fullRegex.matchEntire(string) ?: return null

      val type =
        match.groupValues.getOrNull(1)?.lowercase(ENGLISH)?.let { Type.fromCode(it) }
          ?: return null

      val tree =
        match.groupValues.getOrNull(2)?.lowercase(ENGLISH)?.let { Tree.fromCode(it) }
          ?: Tree.Standard

      val subType = match.groupValues.getOrNull(3)?.lowercase(ENGLISH) ?: return null

      val suffix = match.groupValues.getOrNull(4)?.lowercase(ENGLISH)?.let { Suffix.fromCode(it) }

      val parameters =
        match.groupValues.getOrNull(5)?.let { params ->
          paramRegex.findAll(params).mapNotNull { param ->
            val key = param.groupValues.getOrNull(1) ?: return@mapNotNull null
            val value = param.groupValues.getOrNull(2) ?: return@mapNotNull null
            key.lowercase(ENGLISH) to value.lowercase(ENGLISH)
          }.toMap()
        } ?: emptyMap()

      return MediaType(type, tree, subType, suffix, parameters)
    }

    private val fullRegex =
      """^([a-z]+|\*)/(x(?:-|\\.)|(?:vnd|prs|x)\.|\*)?([a-z0-9\-.]+|\*)(?:\+([a-z]+))?( *(?:; *[\w.-]+ *= *[\w.-]+ *)*)$""" // ktlint-disable max-line-length
        .toRegex(option = IGNORE_CASE)
    private val paramRegex = """ *; *([\w.-]+) *= *([\w.-]+)""".toRegex(option = IGNORE_CASE)

    val Plain = MediaType(Type.Text, subtype = "plain")
    val HTML = MediaType(Type.Text, subtype = "html")
    val JSON = MediaType(Type.Application, subtype = "json")
    val YAML = MediaType(Type.Application, subtype = "yaml")
    val CBOR = MediaType(Type.Application, subtype = "cbor")
    val EventStream = MediaType(Type.Text, subtype = "event-stream")
    val OctetStream = MediaType(Type.Application, subtype = "octet-stream")
    val WWWFormUrlEncoded =
      MediaType(Type.Application, Tree.Obsolete, subtype = "www-form-urlencoded")
    val X509CACert =
      MediaType(Type.Application, Tree.Obsolete, subtype = "x509-ca-cert")
    val X509UserCert =
      MediaType(Type.Application, Tree.Obsolete, subtype = "x509-user-cert")

    val Any = MediaType(Type.Any, subtype = "*")
    val AnyText = MediaType(Type.Text, subtype = "*")
    val AnyImage = MediaType(Type.Image, subtype = "*")
    val AnyAudio = MediaType(Type.Audio, subtype = "*")
    val AnyVideo = MediaType(Type.Video, subtype = "*")

    val JSONStructured = MediaType(Type.Any, Tree.Any, subtype = "*", suffix = Suffix.JSON)
    val XMLStructured = MediaType(Type.Any, Tree.Any, subtype = "*", suffix = Suffix.XML)

    val ProblemJSON = MediaType(Type.Application, subtype = "problem", suffix = Suffix.JSON)
  }
}
