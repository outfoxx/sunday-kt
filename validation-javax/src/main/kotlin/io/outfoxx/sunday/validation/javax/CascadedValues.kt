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

package io.outfoxx.sunday.validation.javax

import io.outfoxx.sunday.validation.ModelGraph
import java.util.IdentityHashMap
import javax.validation.Constraint
import javax.validation.ConstraintValidator
import javax.validation.ConstraintValidatorContext
import javax.validation.ConstraintValidatorContext.ConstraintViolationBuilder
import javax.validation.ElementKind
import javax.validation.Payload
import kotlin.reflect.KClass

/**
 * Cascades an explicitly selected payload mode through containers whose element type is erased.
 *
 * Generated extension storage selects request and response groups independently. The adapter
 * replaces ordinary cascade metadata on that storage, so native model constraints run once.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@JvmRepeatable(CascadedValues.List::class)
@Constraint(validatedBy = [CascadedValues.Validator::class])
annotation class CascadedValues(
  val mode: KClass<*>,
  val message: String = "Invalid nested model",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
) {
  /** Holds the independent payload-mode constraints on the same native field. */
  @Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER)
  @Retention(AnnotationRetention.RUNTIME)
  annotation class List(
    val value: Array<CascadedValues>,
  )

  /** Delegates concrete model predicates to the configured native provider without encoding values. */
  class Validator : ConstraintValidator<CascadedValues, Any> {
    private lateinit var mode: Class<*>

    override fun initialize(annotation: CascadedValues) {
      mode = annotation.mode.java
    }

    override fun isValid(
      value: Any?,
      context: ConstraintValidatorContext,
    ): Boolean {
      context.disableDefaultConstraintViolation()
      val ancestors = IdentityHashMap<Any, Boolean>()
      var valid = true
      val validator = ModelValidation.validatorProvider()

      fun report(
        reason: String,
        path: kotlin.collections.List<ModelGraph.Edge>,
      ) {
        valid = false
        val violation = context.buildConstraintViolationWithTemplate(reason)
        append(
          path,
          violation::addPropertyNode,
          violation::addContainerElementNode,
        ) { violation.addConstraintViolation() }
      }

      fun visit(
        candidate: Any?,
        path: kotlin.collections.List<ModelGraph.Edge>,
      ) {
        if (candidate == null) return
        if (ancestors.put(candidate, true) != null) {
          report("sunday.validation.cycle", path)
          return
        }
        try {
          when (candidate) {
            is Map<*, *> ->
              candidate.entries.sortedBy { it.key.toString() }.forEach {
                visit(it.value, path + ModelGraph.Edge.Entry(it.key.toString()))
              }
            is Iterable<*> ->
              candidate.forEachIndexed { index, element ->
                visit(
                  element,
                  path + ModelGraph.Edge.Element(index),
                )
              }
            is Array<*> ->
              candidate.forEachIndexed { index, element ->
                visit(
                  element,
                  path + ModelGraph.Edge.Element(index),
                )
              }
            else -> {
              val cycle = ModelGraph.cycle(candidate)
              if (cycle != null) {
                report("sunday.validation.cycle", path + cycle)
                return
              }
              validator.validate(candidate, mode).forEach { violation ->
                val nested =
                  violation.propertyPath.flatMap { node ->
                    buildList {
                      if (node.isInIterable) {
                        node.index?.let { add(ModelGraph.Edge.Element(it)) }
                        node.key?.let { add(ModelGraph.Edge.Entry(it.toString())) }
                      }
                      if (node.kind == ElementKind.PROPERTY) add(ModelGraph.Edge.Property(node.name))
                    }
                  }
                report(violation.messageTemplate, path + nested)
              }
            }
          }
        } finally {
          ancestors.remove(candidate)
        }
      }
      visit(value, emptyList())
      return valid
    }

    private fun append(
      path: kotlin.collections.List<ModelGraph.Edge>,
      property: (String) -> ConstraintViolationBuilder.NodeBuilderCustomizableContext,
      container: (String?, Class<*>, Int) -> ConstraintViolationBuilder.ContainerElementNodeBuilderCustomizableContext,
      complete: () -> Unit,
    ) {
      when (val edge = path.firstOrNull()) {
        null -> complete()
        is ModelGraph.Edge.Property -> {
          val node = property(edge.name)
          append(path.drop(1), node::addPropertyNode, node::addContainerElementNode) { node.addConstraintViolation() }
        }
        is ModelGraph.Edge.Element -> {
          val node = container(null, kotlin.collections.List::class.java, 0).inIterable().atIndex(edge.index)
          append(path.drop(1), node::addPropertyNode, node::addContainerElementNode) { node.addConstraintViolation() }
        }
        is ModelGraph.Edge.Entry -> {
          val node = container(null, Map::class.java, 1).inIterable().atKey(edge.key)
          append(path.drop(1), node::addPropertyNode, node::addContainerElementNode) { node.addConstraintViolation() }
        }
      }
    }
  }
}
