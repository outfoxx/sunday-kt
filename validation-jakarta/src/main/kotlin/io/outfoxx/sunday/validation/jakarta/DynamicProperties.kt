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

package io.outfoxx.sunday.validation.jakarta

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import io.outfoxx.sunday.validation.ModelGraph
import io.outfoxx.sunday.validation.NominalValue
import io.outfoxx.sunday.validation.SchemaRules
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder
import jakarta.validation.ElementKind
import jakarta.validation.Payload
import kotlin.reflect.KClass

/** Native containing-property constraints for pattern and additional-property values. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [DynamicProperties.Validator::class])
annotation class DynamicProperties(
  val declared: Array<String> = [],
  val properties: Array<Property> = [],
  val patterns: Array<Pattern> = [],
  val additional: Array<Value> = [],
  val closed: Boolean = false,
  val message: String = "Invalid dynamic property",
  val groups: Array<KClass<*>> = [],
  val payload: Array<KClass<out Payload>> = [],
) {
  companion object {
    /** Reads the same metadata used by native constructor validation, without creating a model. */
    fun from(schema: Class<*>): DynamicProperties =
      schema.declaredConstructors
        .single { it.parameterCount == 1 && !it.isSynthetic }
        .parameters
        .single()
        .getAnnotation(DynamicProperties::class.java)
  }

  /** Common union fields are assertions on a payload, not properties on every reusable branch. */
  annotation class Property(
    val name: String,
    val required: Boolean,
    val schema: Schema,
    val shape: Array<Shape> = [],
  )

  /** Every matching pattern applies; additional constraints apply only to unmatched, undeclared fields. */
  annotation class Pattern(
    val expression: String,
    val schema: Schema,
    val kind: Kind = Kind.ANY,
    val shape: Array<Shape> = [],
  )

  /** One independently inherited additional-property assertion. */
  annotation class Value(
    val schema: Schema,
    val shape: Array<Shape> = [],
  )

  /** A value and its successive collection element types, in outermost-first order. */
  annotation class Shape(
    val kind: Kind,
    val nullable: Boolean = false,
    val model: KClass<*> = Any::class,
  )

  /** Maps a structural object view to its existing native property constraints. */
  @Target(AnnotationTarget.CLASS)
  @Retention(AnnotationRetention.RUNTIME)
  annotation class ObjectValue(
    val fields: Array<Field>,
    val dynamic: KClass<*> = Any::class,
  )

  /** Wire names, presence, and type information; schema predicates remain on the model properties. */
  annotation class Field(
    val name: String,
    val wireName: String,
    val required: Boolean,
    val shape: Array<Shape>,
  )

  /** Primitive wire categories, retained independently of a decoder's numeric and string coercions. */
  enum class Kind {
    ANY,
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT,
    ;

    /** Checks the unconverted wire category without constructing or serializing application models. */
    fun accepts(candidate: Any?): Boolean {
      val value = if (candidate is NominalValue<*>) candidate.value else candidate
      if (value == null || value is JsonNode && value.isNull) return true
      if (value is JsonNode) {
        return when (this) {
          ANY -> true
          STRING -> value.isTextual
          INTEGER -> value.isIntegralNumber
          NUMBER -> value.isNumber
          BOOLEAN -> value.isBoolean
          ARRAY -> value.isArray
          OBJECT -> value.isObject
        }
      }
      return when (this) {
        ANY -> true
        STRING ->
          value is CharSequence ||
            value is java.time.temporal.TemporalAccessor ||
            value is java.net.URI ||
            value is java.net.URL ||
            value is java.util.UUID ||
            value is ByteArray
        INTEGER ->
          value is Number &&
            value
              .toString()
              .toBigDecimalOrNull()
              ?.stripTrailingZeros()
              ?.scale()
              ?.let { it <= 0 } == true
        NUMBER -> value is Number && value.toString().toBigDecimalOrNull() != null
        BOOLEAN -> value is Boolean
        ARRAY -> value is Collection<*> || value is Array<*>
        OBJECT -> value is Map<*, *> || value is ModelGraph
      }
    }
  }

  /** Executes native rules against current values, retaining already-decoded model identity. */
  class Validator(
    private val wireInput: Boolean = true,
  ) : ConstraintValidator<DynamicProperties, Map<String, Any?>> {
    private lateinit var declared: Set<String>

    private data class Rule(
      val schema: SchemaRules,
      val kind: Kind,
      val shape: List<Shape>,
    )

    private var properties: List<Pair<Property, Rule>> = emptyList()
    private lateinit var patterns: List<Pair<Regex, Rule>>
    private var additional: List<Rule> = emptyList()
    private var closed = false

    override fun initialize(annotation: DynamicProperties) {
      declared = annotation.declared.toSet()
      properties = annotation.properties.map { it to Rule(it.schema.rules(), Kind.ANY, it.shape.toList()) }
      patterns = annotation.patterns.map { Regex(it.expression) to Rule(it.schema.rules(), it.kind, it.shape.toList()) }
      additional = annotation.additional.map { Rule(it.schema.rules(), Kind.ANY, it.shape.toList()) }
      closed = annotation.closed
    }

    override fun isValid(
      value: Map<String, Any?>?,
      context: ConstraintValidatorContext,
    ): Boolean {
      if (value == null) return true
      context.disableDefaultConstraintViolation()
      var valid = true
      failures(value) { reason, path ->
        valid = false
        val node = context.buildConstraintViolationWithTemplate(reason)
        append(path, node::addPropertyNode, node::addContainerElementNode) { node.addConstraintViolation() }
      }
      return valid
    }

    private fun failures(
      value: Map<String, Any?>,
      report: (String, List<ModelGraph.Edge>) -> Unit,
    ) {
      for ((property, rule) in properties) {
        val path = listOf(ModelGraph.Edge.Property(property.name))
        if (property.name !in value) {
          if (property.required) report("sunday.validation.required", path)
        } else {
          ruleFailures(value[property.name], rule, path, report)
        }
      }
      for (key in value.keys.sorted()) {
        val candidate = value[key]
        val path = listOf(ModelGraph.Edge.Property(key))
        val matching = patterns.filter { it.first.containsMatchIn(key) }
        val rules =
          if (matching.isNotEmpty()) {
            matching.map { it.second }
          } else if (key in declared) {
            emptyList()
          } else {
            additional
          }
        if (matching.isEmpty() && key !in declared && closed) report("sunday.validation.additionalProperty", path)
        rules.forEach { ruleFailures(candidate, it, path, report) }
      }
    }

    private fun ruleFailures(
      candidate: Any?,
      rule: Rule,
      path: List<ModelGraph.Edge>,
      report: (String, List<ModelGraph.Edge>) -> Unit,
    ) {
      if (ModelGraph.cycle(candidate) != null) {
        report("sunday.validation.cycle", path)
        return
      }
      val (schema, kind, shape) = rule
      if (shape.isNotEmpty()) shapeFailures(candidate, shape, 0, path, report)
      if (shape.isEmpty() && !kind.accepts(candidate)) {
        report("sunday.validation.invalidValue", path)
      } else {
        schema.failures(candidate) { reason, index ->
          report("sunday.validation.$reason", path + listOfNotNull(index?.let { ModelGraph.Edge.Element(it) }))
        }
      }
    }

    private fun shapeFailures(
      candidate: Any?,
      shape: List<Shape>,
      depth: Int,
      path: List<ModelGraph.Edge>,
      report: (String, List<ModelGraph.Edge>) -> Unit,
    ) {
      val type = shape.getOrNull(depth) ?: return
      if (candidate == null || candidate is JsonNode && candidate.isNull) {
        if (!type.nullable) report("sunday.validation.required", path)
        return
      }
      if (type.model != Any::class) {
        if (type.model.java.isInstance(candidate)) return
        if (candidate is ModelGraph && type.kind == Kind.OBJECT) {
          objectFailures(candidate.validationFields(), type.model.java, path, report)
          return
        }
        if (wireInput && candidate is JsonNode) {
          if (type.model.java.isEnum && candidate.isTextual) {
            val constants =
              type.model.java.declaredFields
                .filter { it.isEnumConstant }
            if (constants.none { it.isAnnotationPresent(JsonEnumDefaultValue::class.java) } &&
              type.model.java.enumConstants.none { constant ->
                val field = type.model.java.getDeclaredField((constant as Enum<*>).name)
                (field.getAnnotation(JsonProperty::class.java)?.value ?: constant.toString()) == candidate.textValue()
              }
            ) {
              report("sunday.validation.allowedValue", path)
            }
            return
          }
          if (candidate.isObject && type.model.java.isAnnotationPresent(ObjectValue::class.java)) {
            objectFailures(candidate.properties().associate { it.key to it.value }, type.model.java, path, report)
            return
          }
        } else {
          report("sunday.validation.invalidValue", path)
          return
        }
      }
      if (!type.kind.accepts(candidate)) {
        report("sunday.validation.invalidValue", path)
        return
      }
      if (depth == shape.lastIndex) return
      when (type.kind) {
        Kind.ARRAY -> {
          val elements =
            when (candidate) {
              is Iterable<*> -> candidate
              is Array<*> -> candidate.asList()
              else -> return
            }
          elements.forEachIndexed { index, value ->
            shapeFailures(value, shape, depth + 1, path + ModelGraph.Edge.Element(index), report)
          }
        }
        Kind.OBJECT -> {
          val entries =
            when (candidate) {
              is JsonNode -> candidate.properties().map { it.key to it.value }
              is Map<*, *> -> candidate.entries.map { it.key.toString() to it.value }
              else -> return
            }
          entries.sortedBy { it.first }.forEach { (key, value) ->
            shapeFailures(value, shape, depth + 1, path + ModelGraph.Edge.Entry(key), report)
          }
        }
        else -> Unit
      }
    }

    private fun objectFailures(
      values: Map<String, Any?>,
      type: Class<*>,
      path: List<ModelGraph.Edge>,
      report: (String, List<ModelGraph.Edge>) -> Unit,
    ) {
      val schema = type.getAnnotation(ObjectValue::class.java)
      if (schema == null) {
        report("sunday.validation.invalidValue", path)
        return
      }
      val validator = ModelValidation.validatorProvider()
      val descriptor = validator.getConstraintsForClass(type)
      for (field in schema.fields) {
        val fieldPath = path + ModelGraph.Edge.Property(field.wireName)
        if (field.wireName !in values) {
          if (field.required) report("sunday.validation.required", fieldPath)
          continue
        }
        val value = values[field.wireName]
        shapeFailures(value, field.shape.toList(), 0, fieldPath, report)
        // validateValue reuses native predicates and deliberately does not cascade;
        // the shape walk handles structural views and the native model graph owns concrete cascades.
        if (descriptor.getConstraintsForProperty(field.name)?.hasConstraints() == true) {
          validator.validateValue(type, field.name, wireValue(value, field.shape.toList())).forEach { violation ->
            val nested =
              violation.propertyPath.drop(1).flatMap { node ->
                buildList {
                  if (node.isInIterable) {
                    node.index?.let { add(ModelGraph.Edge.Element(it)) }
                    node.key?.let { add(ModelGraph.Edge.Entry(it.toString())) }
                  }
                  if (node.kind == ElementKind.PROPERTY) add(ModelGraph.Edge.Property(node.name))
                }
              }
            report(violation.messageTemplate, fieldPath + nested)
          }
        }
      }
      if (schema.dynamic != Any::class) {
        val dynamic = Validator(wireInput).apply { initialize(from(schema.dynamic.java)) }
        dynamic.failures(values) { reason, nested -> report(reason, path + nested) }
      }
    }

    private fun wireValue(
      value: Any?,
      shape: List<Shape>,
      depth: Int = 0,
    ): Any? =
      if (value is JsonNode) {
        when {
          value.isNull -> null
          value.isTextual -> value.textValue()
          value.isNumber -> value.decimalValue()
          value.isBoolean -> value.booleanValue()
          value.isArray -> value.map { wireValue(it, shape, depth + 1) }
          value.isObject && shape.getOrNull(depth)?.model == Any::class ->
            value.properties().associate { it.key to wireValue(it.value, shape, depth + 1) }
          value.isObject -> WireModelValue(value)
          else -> value
        }
      } else {
        value
      }

    private fun append(
      path: List<ModelGraph.Edge>,
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
          val node = container(null, List::class.java, 0).inIterable().atIndex(edge.index)
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
