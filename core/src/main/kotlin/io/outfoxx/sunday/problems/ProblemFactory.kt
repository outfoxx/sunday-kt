package io.outfoxx.sunday.problems

import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.Status
import java.net.URI

interface ProblemFactory {

  data class Descriptor(
    val type: URI = URI.create("about:blank"),
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: URI? = null,
    val extensions: Map<String, Any?> = mapOf(),
  )

  interface Builder {
    fun detail(detail: String?): Builder

    fun instance(uri: URI): Builder

    fun extension(
      name: String,
      value: Any?,
    ): Builder

    fun build(): Problem
  }

  fun typed(type: URI): Builder

  fun from(status: Status): Builder

  fun from(descriptor: Descriptor): Problem

  fun from(response: Response): Builder = Status.valueOf(response.statusCode, response.reasonPhrase).let(::from)

  fun adapter(): ProblemAdapter
}
