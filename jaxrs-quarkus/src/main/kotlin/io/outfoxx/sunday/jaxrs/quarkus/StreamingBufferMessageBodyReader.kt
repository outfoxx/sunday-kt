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

package io.outfoxx.sunday.jaxrs.quarkus

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.infrastructure.Infrastructure
import io.vertx.mutiny.core.buffer.Buffer
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.InternalServerErrorException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.ext.Provider
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveResourceInfo
import org.jboss.resteasy.reactive.server.spi.ServerMessageBodyReader
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext
import org.jboss.resteasy.reactive.server.vertx.VertxResteasyReactiveRequestContext
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.Arrays
import java.util.concurrent.Flow

/**
 * Reads a Quarkus request body as a demand-driven stream of Vert.x buffers.
 */
@Provider
@Consumes("*/*")
class StreamingBufferMessageBodyReader : ServerMessageBodyReader<Multi<Buffer>> {

  override fun isReadable(
    type: Class<*>,
    genericType: Type,
    annotations: Array<out Annotation>,
    mediaType: MediaType,
  ): Boolean = isStreamingBufferMulti(type, genericType)

  override fun isReadable(
    type: Class<*>,
    genericType: Type,
    lazyMethod: ResteasyReactiveResourceInfo,
    mediaType: MediaType,
  ): Boolean = isStreamingBufferMulti(type, genericType)

  override fun readFrom(
    type: Class<Multi<Buffer>>,
    genericType: Type,
    annotations: Array<out Annotation>,
    mediaType: MediaType,
    httpHeaders: MultivaluedMap<String, String>,
    entityStream: InputStream,
  ): Multi<Buffer> =
    throw UnsupportedOperationException(
      "Streaming request bodies require Quarkus RESTEasy Reactive server request context",
    )

  override fun readFrom(
    type: Class<Multi<Buffer>>,
    genericType: Type,
    mediaType: MediaType,
    context: ServerRequestContext,
  ): Multi<Buffer> {
    if (context !is VertxResteasyReactiveRequestContext) {
      throw InternalServerErrorException(
        "Streaming request bodies require Vert.x-backed Quarkus RESTEasy Reactive request context",
      )
    }

    return Multi
      .createFrom()
      .publisher(StreamingBufferPublisher(context.inputStream))
  }

  private fun isStreamingBufferMulti(
    type: Class<*>,
    genericType: Type,
  ): Boolean {
    if (type != Multi::class.java) {
      return false
    }

    val parameterizedType = genericType as? ParameterizedType ?: return false
    if (parameterizedType.rawType != Multi::class.java) {
      return false
    }

    return parameterizedType.actualTypeArguments.singleOrNull() == Buffer::class.java
  }

  private class StreamingBufferPublisher(
    private val inputStream: InputStream,
  ) : Flow.Publisher<Buffer> {

    override fun subscribe(subscriber: Flow.Subscriber<in Buffer>) {
      val subscription = StreamingBufferSubscription(inputStream, subscriber)
      subscriber.onSubscribe(subscription)
    }
  }

  private class StreamingBufferSubscription(
    private val inputStream: InputStream,
    private val subscriber: Flow.Subscriber<in Buffer>,
  ) : Flow.Subscription {

    private var demand: Long = 0
    private var reading = false
    private var terminated = false

    override fun request(n: Long) {
      if (n <= 0) {
        fail(IllegalArgumentException("Reactive stream demand must be positive"))
        return
      }

      synchronized(this) {
        demand = addDemand(demand, n)
      }

      readIfNeeded()
    }

    override fun cancel() {
      cleanup()
    }

    private fun readIfNeeded() {
      val shouldRead =
        synchronized(this) {
          if (terminated || reading || demand <= 0) {
            false
          } else {
            reading = true
            true
          }
        }

      if (shouldRead) {
        Infrastructure.getDefaultExecutor().execute(::readUntilDemandSatisfied)
      }
    }

    private fun readUntilDemandSatisfied() {
      val bytes = ByteArray(CHUNK_SIZE)

      try {
        while (tryReserveDemand()) {
          val count = inputStream.read(bytes)
          if (count < 0) {
            complete()
            return
          }
          if (count > 0) {
            subscriber.onNext(Buffer.buffer(Arrays.copyOf(bytes, count)))
          }
        }
      } catch (failure: Throwable) {
        fail(failure)
        return
      }

      synchronized(this) {
        reading = false
      }

      readIfNeeded()
    }

    private fun tryReserveDemand(): Boolean =
      synchronized(this) {
        if (terminated || demand <= 0) {
          false
        } else {
          if (demand != Long.MAX_VALUE) {
            demand -= 1
          }
          true
        }
      }

    private fun complete() {
      if (cleanup()) {
        subscriber.onComplete()
      }
    }

    private fun fail(failure: Throwable) {
      if (cleanup()) {
        subscriber.onError(failure)
      }
    }

    private fun cleanup(): Boolean {
      val shouldSignal =
        synchronized(this) {
          if (terminated) {
            false
          } else {
            terminated = true
            true
          }
        }

      if (shouldSignal) {
        try {
          inputStream.close()
        } catch (_: IOException) {
        }
      }

      return shouldSignal
    }

    private fun addDemand(
      current: Long,
      requested: Long,
    ): Long {
      val total = current + requested
      return if (total < 0) Long.MAX_VALUE else total
    }

    companion object {
      private const val CHUNK_SIZE = 8 * 1024
    }
  }
}
