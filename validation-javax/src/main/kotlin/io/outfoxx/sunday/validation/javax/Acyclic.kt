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
import javax.validation.Constraint
import javax.validation.ConstraintValidator
import javax.validation.ConstraintValidatorContext
import javax.validation.ConstraintValidatorContext.ConstraintViolationBuilder
import javax.validation.Payload
import kotlin.reflect.KClass

/** Rejects object cycles while allowing the same value to occur along independent paths. */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FIELD,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [Acyclic.Validator::class])
annotation class Acyclic(
  val message: String = "sunday.validation.cycle",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
) {
  /** Reports the cycle through native property and container nodes without invoking model constructors. */
  class Validator : ConstraintValidator<Acyclic, Any> {
    override fun isValid(
      value: Any?,
      context: ConstraintValidatorContext,
    ): Boolean {
      val path = ModelGraph.cycle(value) ?: return true
      context.disableDefaultConstraintViolation()
      val violation = context.buildConstraintViolationWithTemplate("sunday.validation.cycle")
      append(
        path,
        violation::addPropertyNode,
        violation::addContainerElementNode,
        { violation.addConstraintViolation() },
      )
      return false
    }

    private fun append(
      path: List<ModelGraph.Edge>,
      property: (String) -> ConstraintViolationBuilder.NodeBuilderCustomizableContext,
      container: (String?, Class<*>, Int) -> ConstraintViolationBuilder.ContainerElementNodeBuilderCustomizableContext,
      complete: () -> Unit,
    ) {
      val edge = path.firstOrNull()
      when (edge) {
        null -> complete()
        is ModelGraph.Edge.Property -> {
          val node = property(edge.name)
          append(path.drop(1), node::addPropertyNode, node::addContainerElementNode, { node.addConstraintViolation() })
        }
        is ModelGraph.Edge.Element -> {
          val node = container(null, List::class.java, 0).inIterable().atIndex(edge.index)
          append(path.drop(1), node::addPropertyNode, node::addContainerElementNode, { node.addConstraintViolation() })
        }
        is ModelGraph.Edge.Entry -> {
          val node = container(null, Map::class.java, 1).inIterable().atKey(edge.key)
          append(path.drop(1), node::addPropertyNode, node::addContainerElementNode, { node.addConstraintViolation() })
        }
      }
    }
  }
}
