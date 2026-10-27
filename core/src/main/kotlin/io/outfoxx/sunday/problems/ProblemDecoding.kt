package io.outfoxx.sunday.problems

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Problem-only normalization before union dispatch; property annotations own value conversion. */
object ProblemDecoding {
  /** Copies the object so branch selection cannot change the caller's input or extension values. */
  @JvmStatic
  fun normalized(value: JsonNode): JsonNode {
    if (value !is ObjectNode) return value
    val result = value.deepCopy()
    for (name in listOf("type", "title", "status", "detail", "instance")) {
      val member = result.get(name) ?: continue
      if (if (name == "status") !member.isNumber else !member.isTextual) result.remove(name)
    }
    if (!result.has("type")) result.put("type", "about:blank")
    return result
  }
}
