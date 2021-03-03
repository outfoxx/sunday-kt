package io.outfoxx.sunday

import java.lang.RuntimeException

class SundayError(message: String, val reason: Reason? = null, cause: Throwable? = null) :
  RuntimeException(message, cause) {

  enum class Reason(val message: String) {
    UnexpectedEmptyResponse("Unexpected empty reason"),
    InvalidContentType("Invalid Content-Type"),
    NoDecoder("No decoder registered for MediaType"),
    ResponseDecodingFailed("Response decoding failed"),
    EventDecodingFailed("Event decoding failed")
  }

  constructor(reason: Reason, extraMessage: String? = null, cause: Throwable? = null)
    : this("${reason.message}${extraMessage?.let { " $it" } ?: ""}", reason, cause)

}
