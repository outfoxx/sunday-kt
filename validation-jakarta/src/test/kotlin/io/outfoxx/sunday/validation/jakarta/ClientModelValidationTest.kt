/*
 * Copyright 2020 Outfox, Inc.
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

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validation
import jakarta.ws.rs.ext.ReaderInterceptorContext
import jakarta.ws.rs.ext.WriterInterceptorContext
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class ClientModelValidationTest {

  class Payload(
    @get:Schema(minLength = 2)
    var text: String,
  )

  @KnownVariant(groups = [ModelMode.Request::class])
  class Unknown

  class Codes(
    @field:Schema(minItems = 1)
    @param:Schema(minItems = 1)
    val value: List<
      @Schema(minLength = 2)
      String,
    >,
  )

  class Pairs(
    @param:Schema(minItems = 2)
    val value: List<String>,
  )

  interface TypedClient {
    @EntitySchema(Pairs::class)
    fun exchange(
      @EntitySchema(Codes::class) body: List<String>,
    ): List<String>
  }

  @Test
  fun `uses request parameter and response method schemas independently`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val previous = ModelValidation.validatorProvider
        ModelValidation.validatorProvider = { factory.validator }
        try {
          val payload = mutableListOf("valid")
          val invoked = TypedClient::class.java.getMethod("exchange", List::class.java)
          val writer =
            Proxy.newProxyInstance(
              WriterInterceptorContext::class.java.classLoader,
              arrayOf(WriterInterceptorContext::class.java),
            ) { _, method, _ ->
              when (method.name) {
                "getEntity" -> payload
                "getProperty" -> invoked
                "getAnnotations" -> arrayOf(EntitySchema(Pairs::class))
                "proceed" -> null
                else -> error("Unexpected writer call: ${method.name}")
              }
            } as WriterInterceptorContext
          val reader =
            Proxy.newProxyInstance(
              ReaderInterceptorContext::class.java.classLoader,
              arrayOf(ReaderInterceptorContext::class.java),
            ) { _, method, _ ->
              when (method.name) {
                "getProperty" -> invoked
                "getAnnotations" -> arrayOf(EntitySchema(Codes::class))
                "proceed" -> payload
                else -> error("Unexpected reader call: ${method.name}")
              }
            } as ReaderInterceptorContext
          val adapter = ClientModelValidation()
          adapter.aroundWriteTo(writer)
          assertThrows(ConstraintViolationException::class.java) { adapter.aroundReadFrom(reader) }
          payload += "valid"
          assertSame(payload, adapter.aroundReadFrom(reader))
          payload[0] = "x"
          assertThrows(ConstraintViolationException::class.java) { adapter.aroundWriteTo(writer) }
        } finally {
          ModelValidation.validatorProvider = previous
        }
      }
  }

  @Test
  fun `validates root schema metadata without invoking a model constructor`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val previous = ModelValidation.validatorProvider
        ModelValidation.validatorProvider = { factory.validator }
        try {
          val payload = mutableListOf("valid")
          var writes = 0
          val writer =
            Proxy.newProxyInstance(
              WriterInterceptorContext::class.java.classLoader,
              arrayOf(WriterInterceptorContext::class.java),
            ) { _, method, _ ->
              when (method.name) {
                "getEntity" -> payload
                "getProperty" -> null
                "getAnnotations" -> arrayOf(EntitySchema(Codes::class))
                "proceed" -> {
                  writes++
                  null
                }
                else -> error("Unexpected writer call: ${method.name}")
              }
            } as WriterInterceptorContext
          val reader =
            Proxy.newProxyInstance(
              ReaderInterceptorContext::class.java.classLoader,
              arrayOf(ReaderInterceptorContext::class.java),
            ) { _, method, _ ->
              when (method.name) {
                "getProperty" -> null
                "getAnnotations" -> arrayOf(EntitySchema(Codes::class))
                "proceed" -> payload
                else -> error("Unexpected reader call: ${method.name}")
              }
            } as ReaderInterceptorContext
          val adapter = ClientModelValidation()
          adapter.aroundWriteTo(writer)
          assertSame(payload, adapter.aroundReadFrom(reader))
          payload[0] = "x"
          assertThrows(ConstraintViolationException::class.java) { adapter.aroundWriteTo(writer) }
          assertThrows(ConstraintViolationException::class.java) { adapter.aroundReadFrom(reader) }
          payload.clear()
          assertThrows(ConstraintViolationException::class.java) { adapter.aroundWriteTo(writer) }
          assertEquals(1, writes)
        } finally {
          ModelValidation.validatorProvider = previous
        }
      }
  }

  @Test
  fun `validates each execution before encoding and preserves the response instance`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val previous = ModelValidation.validatorProvider
        ModelValidation.validatorProvider = { factory.validator }
        try {
          val payload = Payload("ok")
          var writes = 0
          val writer =
            Proxy.newProxyInstance(
              WriterInterceptorContext::class.java.classLoader,
              arrayOf(WriterInterceptorContext::class.java),
            ) { _, method, _ ->
              when (method.name) {
                "getProperty" -> null
                "getAnnotations" -> emptyArray<Annotation>()
                "getEntity" -> payload
                "proceed" -> {
                  writes++
                  null
                }
                else -> error("Unexpected writer call: ${method.name}")
              }
            } as WriterInterceptorContext
          val adapter = ClientModelValidation()
          adapter.aroundWriteTo(writer)
          assertEquals(1, writes)
          payload.text = "x"
          assertThrows(ConstraintViolationException::class.java) { adapter.aroundWriteTo(writer) }
          assertEquals(1, writes)
          payload.text = "valid"
          adapter.aroundWriteTo(writer)
          assertEquals(2, writes)
          val reader =
            Proxy.newProxyInstance(
              ReaderInterceptorContext::class.java.classLoader,
              arrayOf(ReaderInterceptorContext::class.java),
            ) { _, method, _ ->
              when (method.name) {
                "getProperty" -> null
                "getAnnotations" -> emptyArray<Annotation>()
                "proceed" -> payload
                else -> error("Unexpected reader call: ${method.name}")
              }
            } as ReaderInterceptorContext
          assertSame(payload, adapter.aroundReadFrom(reader))
          payload.text = "x"
          assertThrows(ConstraintViolationException::class.java) { adapter.aroundReadFrom(reader) }
          assertThrows(ConstraintViolationException::class.java) { ModelValidation.request(Unknown()) }
          ModelValidation.response(Unknown())
        } finally {
          ModelValidation.validatorProvider = previous
        }
      }
  }
}
