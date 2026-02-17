package io.outfoxx.sunday.problems

import org.zalando.problem.StatusType

data class NonStandardStatus(
  private val statusCode: Int,
  private val reasonPhrase: String?,
) : StatusType {

  override fun getStatusCode() = statusCode

  override fun getReasonPhrase() = reasonPhrase
}
