package io.outfoxx.sunday.problems

import io.outfoxx.sunday.http.Status
import java.net.URI

interface ProblemAdapter {

  fun getType(problem: Problem): URI

  fun getTitle(problem: Problem): String?

  fun getStatus(problem: Problem): Status?

  fun getDetail(problem: Problem): String?

  fun getInstance(problem: Problem): URI?

  fun getExtensions(problem: Problem): Map<String, Any?>

  fun getExtension(
    problem: Problem,
    name: String,
  ): Any? = getExtensions(problem)[name]
}
