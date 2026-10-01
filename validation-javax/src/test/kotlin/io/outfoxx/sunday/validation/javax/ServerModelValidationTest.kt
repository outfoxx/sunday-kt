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

package io.outfoxx.sunday.validation.javax

import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import javax.validation.ConstraintViolationException
import javax.validation.Validation
import javax.ws.rs.InternalServerErrorException
import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.container.ContainerResponseContext
import javax.ws.rs.ext.WriterInterceptorContext

class ServerModelValidationTest {
  class Payload(
    @get:Schema(minLength = 2) var text: String,
  )

  @Test
  fun `checks marked successful responses and leaves errors and unmarked codecs alone`() {
    Validation
      .byDefaultProvider()
      .configure()
      .messageInterpolator(ParameterMessageInterpolator())
      .buildValidatorFactory()
      .use { factory ->
        val previous = ModelValidation.validatorProvider
        ModelValidation.validatorProvider = { factory.validator }
        try {
          val properties = mutableMapOf<String, Any?>()
          val payload = Payload("valid")
          var annotations: Array<Annotation>? = arrayOf(EntitySchema())
          var writes = 0
          val writer =
            Proxy.newProxyInstance(
              WriterInterceptorContext::class.java.classLoader,
              arrayOf(WriterInterceptorContext::class.java),
            ) { _, method, arguments ->
              when (method.name) {
                "getAnnotations" -> annotations
                "getEntity" -> payload
                "getProperty" -> properties[arguments!![0]]
                "proceed" -> {
                  writes++
                  null
                }
                else -> error("Unexpected writer call: ${method.name}")
              }
            } as WriterInterceptorContext
          val adapter = ServerModelValidation()
          adapter.aroundWriteTo(writer)
          payload.text = "x"
          val failure = assertThrows(InternalServerErrorException::class.java) { adapter.aroundWriteTo(writer) }
          assertTrue(failure.cause is ConstraintViolationException)
          assertEquals(1, writes)
          annotations = null
          adapter.aroundWriteTo(writer)
          assertEquals(2, writes)
          annotations = arrayOf(EntitySchema())
          val request =
            Proxy.newProxyInstance(
              ContainerRequestContext::class.java.classLoader,
              arrayOf(ContainerRequestContext::class.java),
            ) { _, method, arguments ->
              when (method.name) {
                "setProperty" -> {
                  properties[arguments!![0] as String] = arguments[1]
                  null
                }
                else -> error("Unexpected request call: ${method.name}")
              }
            } as ContainerRequestContext
          val response =
            Proxy.newProxyInstance(
              ContainerResponseContext::class.java.classLoader,
              arrayOf(ContainerResponseContext::class.java),
            ) { _, method, _ ->
              when (method.name) {
                "getStatus" -> 403
                else -> error("Unexpected response call: ${method.name}")
              }
            } as ContainerResponseContext
          adapter.filter(request, response)
          adapter.aroundWriteTo(writer)
          assertEquals(3, writes)
        } finally {
          ModelValidation.validatorProvider = previous
        }
      }
  }
}
