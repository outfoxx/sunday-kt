/*
 * Copyright 2026 Outfox, Inc.
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

package io.outfoxx.sunday.validation

import com.fasterxml.jackson.databind.JsonNode
import java.util.IdentityHashMap

/** Supplies current participating wire fields without serializing or reconstructing a model. */
interface ModelGraph {
  /** Returns field views for this invocation; implementations must not cache mutable model values. */
  fun validationFields(): Map<String, Any?>

  /** A wire location reached while checking whether a value can be serialized without cycles. */
  sealed interface Edge {
    /** A declared wire field, including its serialization alias. */
    data class Property(
      val name: String,
    ) : Edge

    /** A zero-based collection position. */
    data class Element(
      val index: Int,
    ) : Edge

    /** A dynamic object property. */
    data class Entry(
      val key: String,
    ) : Edge
  }

  companion object {
    /** Returns the first cycle in deterministic wire order, permitting ordinary shared references. */
    fun cycle(value: Any?): List<Edge>? {
      val ancestors = IdentityHashMap<Any, Boolean>()
      val path = mutableListOf<Edge>()

      fun visit(candidate: Any?): List<Edge>? {
        if (candidate !is ModelGraph &&
          candidate !is Map<*, *> &&
          candidate !is Iterable<*> &&
          candidate !is Array<*>
        ) {
          return null
        }
        if (ancestors.put(candidate, true) != null) return path.toList()
        try {
          val children: Sequence<Pair<Edge, Any?>> =
            when (candidate) {
              is ModelGraph -> candidate.validationFields().asSequence().map { Edge.Property(it.key) to it.value }
              is JsonNode ->
                if (candidate.isObject) {
                  candidate
                    .properties()
                    .sortedBy { it.key }
                    .asSequence()
                    .map { Edge.Entry(it.key) to it.value }
                } else {
                  candidate.asSequence().mapIndexed { index, item -> Edge.Element(index) to item }
                }
              is Map<*, *> ->
                candidate.entries.sortedBy { it.key.toString() }.asSequence().map {
                  Edge.Entry(it.key.toString()) to
                    it.value
                }
              is Iterable<*> -> candidate.asSequence().mapIndexed { index, item -> Edge.Element(index) to item }
              is Array<*> -> candidate.asSequence().mapIndexed { index, item -> Edge.Element(index) to item }
              else -> error("Unreachable model graph value")
            }
          for ((edge, child) in children) {
            path += edge
            visit(child)?.let { return it }
            path.removeAt(path.lastIndex)
          }
          return null
        } finally {
          ancestors.remove(candidate)
        }
      }
      return visit(value)
    }
  }
}
