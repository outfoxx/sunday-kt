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

/**
 * MIME Media type with support for parameters.
 */
class MediaType(
  val type: Type,
  val tree: Tree = Tree.Standard,
  subtype: String = "*",
  val suffix: Suffix? = null,
  parameters: Map<String, String> = emptyMap(),
) {

  constructor(
    type: Type,
    tree: Tree = Tree.Standard,
    subtype: String = "*",
    suffix: Suffix? = null,
    vararg parameters: Pair<String, String>,
  ) : this(type, tree, subtype, suffix, mapOf(*parameters))

  /**
   * Standard parameter names.
   */
  enum class StandardParameterName(
    val code: String,
  ) {
    CharSet("charset"),
  }

  /**
   * Allowed types of media type.
   */
  enum class Type(
    val code: String,
  ) {
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
    Any("*"),
    ;

    companion object {

      /**
       * Looks up a [Type] from a media type code.
       *
       * @param code Code name of type.
       * @return [Type] instance matching the [code].
       */
      fun fromCode(code: String) = values().firstOrNull { it.code == code }
    }
  }

  /**
   * Allowed trees of media type.
   */
  enum class Tree(
    val code: String,
  ) {
    Standard(""),
    Vendor("vnd."),
    Personal("prs."),
    Unregistered("x."),
    Obsolete("x-"),
    Any("*"),
    ;

    companion object {

      /**
       * Looks up a [Tree] from a media type code.
       *
       * @param code Code name of tree.
       * @return [Tree] instance matching the [code].
       */
      fun fromCode(code: String) = values().firstOrNull { it.code == code }
    }
  }

  /**
   * Allowed suffixes of media type.
   */
  enum class Suffix(
    val code: String,
  ) {
    XML("xml"),
    JSON("json"),
    BER("ber"),
    DER("der"),
    FastInfoSet("fastinfoset"),
    WBXML("wbxml"),
    Zip("zip"),
    CBOR("cbor"),
    ;

    companion object {

      /**
       * Looks up a [Suffix] from a media type code.
       *
       * @param code Code name of suffix.
       * @return [Suffix] instance matching the [code].
       */
      fun fromCode(code: String) = values().firstOrNull { it.code == code }
    }
  }

  /**
   * Subtype of the media type.
   */
  val subtype = subtype.lowercase(ENGLISH)

  /**
   * Parameters of the media type.
   */
  val parameters =
    parameters
      .map { it.key.lowercase(ENGLISH) to it.value.lowercase(ENGLISH) }
      .toMap()

  /**
   * Looks up a parameter using a [standard parameter name][StandardParameterName].
   *
   * @param name Standard parameter name to lookup.
   * @return Value of parameter if it exists or null.
   */
  fun parameter(name: StandardParameterName): String? = parameters[name.code]

  /**
   * Looks up a parameter.
   *
   * @param name Parameter name to lookup.
   * @return Value of parameter if it exists or null.
   */
  fun parameter(name: String): String? = parameters[name]

  /**
   * Builds a new [MediaType] overriding one or more of the properties.
   *
   * @param type [Type] to override.
   * @param tree [Tree]] to override.
   * @param subtype Subtype to override.
   * @param parameters Parameters to override.
   * @return [MediaType] instance with the overridden properties.
   */
  fun with(
    type: Type? = null,
    tree: Tree? = null,
    subtype: String? = null,
    parameters: Map<String, String>? = null,
  ) = MediaType(
    type ?: this.type,
    tree ?: this.tree,
    subtype ?: this.subtype,
    suffix,
    parameters ?: this.parameters,
  )

  /**
   * Builds a new [MediaType] overriding a parameter value.
   *
   * @param parameter Parameter name to override.
   * @param value Overridden parameter value.
   * @return [MediaType] instance with the overridden parameter.
   */
  fun with(
    parameter: StandardParameterName,
    value: String,
  ) = with(parameters = parameters + mapOf(parameter.code to value))

  /**
   * Builds a new [MediaType] overriding a parameter value.
   *
   * @param parameter Parameter name to override.
   * @param value Overridden parameter value.
   * @return [MediaType] instance with the overridden parameter.
   */
  fun with(
    parameter: String,
    value: String,
  ) = with(parameters = parameters + mapOf(parameter to value))

  /**
   * Encoded media type.
   */
  val value: String
    get() {
      val type = this.type.code
      val tree = this.tree.code
      val suffix = this.suffix?.let { "+${it.name.lowercase(ENGLISH)}" } ?: ""
      val parameters =
        this.parameters.keys
          .sorted()
          .joinToString("") { ";$it=${parameters[it]}" }
      return "$type/$tree$subtype$suffix$parameters"
    }

  /**
   * Check if a given [media type][MediaType] is compatible with this instance.
   *
   * @see other [MediaType] to check for compatibility.
   * @return `true` if [other] is compatible with this instance.
   */
  fun compatible(other: MediaType): Boolean =
    when {
      this.type != Type.Any && other.type != Type.Any && this.type != other.type -> false
      this.tree != Tree.Any && other.tree != Tree.Any && this.tree != other.tree -> false
      this.subtype != "*" && other.subtype != "*" && this.subtype != other.subtype -> false
      this.suffix != other.suffix -> false
      else ->
        this.parameters.keys
          .intersect(other.parameters.keys)
          .all { this.parameters[it] == other.parameters[it] }
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

    /**
     * Parses a list of Accept headers into a list of [media types][MediaType].
     *
     * @param acceptHeaders Accept headers to parse into media types.
     * @return List of [media types][MediaType] parsed from the given Accept headers.
     */
    fun from(acceptHeaders: List<String>): List<MediaType> =
      acceptHeaders.flatMap { header -> header.split(",") }.map { from(it.trim()) }

    /**
     * Parse a string into a [media type][MediaType].
     *
     * @param string String media type to parse.
     * @return [MediaType] parsed from the given string.
     */
    fun from(
      string: String,
      default: MediaType? = null,
    ): MediaType {
      fun default(): MediaType {
        if (default == null) {
          throw SundayError(SundayError.Reason.InvalidContentType, string)
        } else {
          return default
        }
      }

      val match = fullRegex.matchEntire(string) ?: return default()

      val type =
        match.groupValues
          .getOrNull(1)
          ?.lowercase(ENGLISH)
          ?.let { Type.fromCode(it) }
          ?: return default()

      val tree =
        match.groupValues
          .getOrNull(2)
          ?.lowercase(ENGLISH)
          ?.let { Tree.fromCode(it) }
          ?: Tree.Standard

      val subType = match.groupValues.getOrNull(3)?.lowercase(ENGLISH) ?: return default()

      val suffix =
        match.groupValues
          .getOrNull(4)
          ?.lowercase(ENGLISH)
          ?.let { Suffix.fromCode(it) }

      val parameters =
        match.groupValues.getOrNull(5)?.let { params ->
          paramRegex
            .findAll(params)
            .mapNotNull { param ->
              val key = param.groupValues.getOrNull(1) ?: return@mapNotNull null
              val value = param.groupValues.getOrNull(2) ?: return@mapNotNull null
              key.lowercase(ENGLISH) to value.lowercase(ENGLISH)
            }.toMap()
        } ?: emptyMap()

      return MediaType(type, tree, subType, suffix, parameters)
    }

    private val fullRegex =
      @Suppress("ktlint:standard:max-line-length")
      """^([a-z]+|\*)/(x(?:-|\\.)|(?:vnd|prs|x)\.|\*)?([a-z0-9\-.]+|\*)(?:\+([a-z]+))?( *(?:; *[\w.-]+ *= *[\w.-]+ *)*)$"""
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

    val Problem = MediaType(Type.Application, subtype = "problem", suffix = Suffix.JSON)

    val JsonPatch = MediaType(Type.Application, subtype = "json-patch", suffix = Suffix.JSON)
    val MergePatch = MediaType(Type.Application, subtype = "merge-patch", suffix = Suffix.JSON)
  }
}
