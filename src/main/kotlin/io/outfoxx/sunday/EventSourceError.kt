package io.outfoxx.sunday

class EventSourceError(
  val reason: Reason,
) : Exception(reason.message) {

  enum class Reason(val message: String) {
    EventTimeout("Event Timeout Reached"),
    InvalidState("Invalid State")
  }

}
