package io.outfoxx.sunday.problems

import com.fasterxml.jackson.annotation.JsonAnySetter
import java.net.URI

/** Internal generic transport representation, sharing the public property annotations. */
internal class ProblemWire(
  @param:ProblemReference val type: URI = URI.create("about:blank"),
  @param:ProblemString val title: String? = null,
  @param:ProblemStatus val status: Int? = null,
  @param:ProblemString val detail: String? = null,
  @param:ProblemReference val instance: URI? = null,
) {
  val extensions: MutableMap<String, Any?> = mutableMapOf()

  @JsonAnySetter
  fun extension(
    name: String,
    value: Any?,
  ) {
    extensions[name] = value
  }
}
