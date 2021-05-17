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

package io.outfoxx.sunday

import io.outfoxx.sunday.EventSource.ReadyState.Closed
import io.outfoxx.sunday.EventSource.ReadyState.Connecting
import io.outfoxx.sunday.EventSource.ReadyState.Open
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.http.HeaderNames.Accept
import io.outfoxx.sunday.http.HeaderNames.LastEventId
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Response
import okhttp3.internal.connection.RealCall
import okhttp3.internal.sse.ServerSentEventReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.lang.Double.max
import java.lang.Double.min
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit.MILLIS
import java.time.temporal.ChronoUnit.SECONDS
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.schedule
import kotlin.concurrent.write
import kotlin.math.pow

class EventSource(
  private val callSupplier: suspend (Headers) -> Call,
  eventTimeout: Duration = Duration.of(75, SECONDS),
  retryTime: Duration = Duration.of(100, MILLIS),
  private val logger: Logger = LoggerFactory.getLogger(EventSource::class.java)
) : Closeable {

  companion object {

    private const val MaxRetryTimeMultiple = 30.0
    private val EventTimeoutCheckInterval = Duration.of(2, SECONDS).toMillis()
  }

  enum class ReadyState {
    Connecting,
    Open,
    Closed
  }

  private val stateLock = ReentrantReadWriteLock()

  private var readyStateValue = Closed

  val readyState: ReadyState
    get() = stateLock.read { readyStateValue }

  private var openHandler: ((source: EventSource) -> Unit)? = null

  var onopen: ((source: EventSource) -> Unit)?
    get() = stateLock.read { openHandler }
    set(value) = stateLock.write { openHandler = value }

  private var errorHandler: ((source: EventSource, error: Throwable?) -> Unit)? = null

  var onerror: ((source: EventSource, error: Throwable?) -> Unit)?
    get() = stateLock.read { errorHandler }
    set(value) = stateLock.write { errorHandler = value }

  private var messageHandler: ((source: EventSource, id: String?, event: String?, data: String?) -> Unit)? = null

  var onmessage: ((source: EventSource, id: String?, event: String?, data: String?) -> Unit)?
    get() = stateLock.read { messageHandler }
    set(value) = stateLock.write { messageHandler = value }

  private var retryTime = retryTime.toMillis()
  private var retryAttempt = 0
  private var currentCall: Call? = null
  private var connectionAttemptTime: Instant? = null
  private var reconnectTimerTask: TimerTask? = null
  private var lastEventId: String? = null
  private var eventTimeout = eventTimeout.toMillis()
  private var eventTimeoutTimerTask: TimerTask? = null
  private var lastEventTime: Instant? = null
  private val eventListeners = mutableMapOf<String, (id: String?, event: String?, data: String?) -> Unit>()

  fun connect() {
    if (readyStateValue == Connecting || readyStateValue == Open) {
      return
    }

    runBlocking {
      internalConnect()
    }
  }

  private suspend fun internalConnect() {
    logger.debug("Connecting")

    readyStateValue = Connecting

    var headers = mapOf(
      Accept to EventStream.value,
    )

    lastEventId?.let { headers = headers.plus(LastEventId to it) }

    connectionAttemptTime = Instant.now()

    currentCall = callSupplier(headers.toHeaders())

    currentCall?.enqueue(object : Callback {

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!response.isSuccessful) {
            receivedError(null)
            return
          }

          val body = response.body!!
          val contentType = body.contentType()?.let { MediaType.from(it.toString()) }

          if (contentType == null || !contentType.compatible(EventStream)) {
            receivedError(IllegalStateException("Invalid content-type: ${body.contentType()}"))
            return
          }

          // This is a long-lived response. Cancel full-call timeouts.
          (call as? RealCall)?.timeoutEarlyExit()

          val reader =
            ServerSentEventReader(
              body.source(),
              object : ServerSentEventReader.Callback {
                override fun onEvent(id: String?, type: String?, data: String) {
                  receivedEvent(id, type, data)
                }

                override fun onRetryChange(timeMs: Long) {
                  retryTime = timeMs
                }
              }
            )

          try {

            receivedOpen()

            @Suppress("ControlFlowWithEmptyBody")
            while (reader.processNextEvent()) {
            }

            receivedComplete()
          } catch (e: Exception) {
            receivedError(e)
            return
          }
        }
      }

      override fun onFailure(call: Call, e: IOException) {
        receivedError(e)
      }
    })
  }

  override fun close() {
    logger.debug("Closed")

    readyStateValue = Closed

    internalClose()
  }

  private fun internalClose() {
    currentCall?.cancel()
    currentCall = null

    clearReconnect()

    stopEventTimeoutCheck()
  }

  private fun startEventTimeoutCheck() {
    stopEventTimeoutCheck()

    if (eventTimeout > 0) {
      return
    }

    eventTimeoutTimerTask =
      Timer("Event Timeout")
        .schedule(EventTimeoutCheckInterval) {
          checkEventTimeout()
        }
  }

  private fun stopEventTimeoutCheck() {

    eventTimeoutTimerTask?.cancel()
    eventTimeoutTimerTask = null
  }

  private fun checkEventTimeout() {
    if (eventTimeout > 0) {
      stopEventTimeoutCheck()
      return
    }

    // Check elapsed time since last received event
    val elapsed = Duration.between(lastEventTime ?: Instant.EPOCH, Instant.now()).toMillis()
    if (elapsed > eventTimeout) {
      logger.debug("Event timeout reached")

      internalClose()
      scheduleReconnect()
    }
  }

  private fun receivedOpen() {
    if (readyStateValue !== Connecting) {
      logger.warn("Invalid Ready State for Open")

      internalClose()
      scheduleReconnect()
    }

    logger.debug("Connected")

    retryAttempt = 0
    readyStateValue = Open

    startEventTimeoutCheck()

    openHandler?.invoke(this)
  }

  private fun receivedEvent(id: String?, event: String?, data: String?) {

    if (event != null) {
      eventListeners[event]?.invoke(id, event, data)
    }

    messageHandler?.invoke(this, id, event, data)
  }

  private fun receivedError(t: Throwable?) {

    if (t is java.util.concurrent.CancellationException) {
      return
    }

    logger.debug("Received error", t)

    scheduleReconnect()

    errorHandler?.invoke(this, t)
  }

  private fun receivedComplete() {

    logger.debug("Received complete")

    if (readyStateValue != Closed) {
      scheduleReconnect()
    }
  }

  private fun scheduleReconnect() {
    // calculate total delay
    val backOffDelay = retryAttempt.toDouble().pow(2) * retryTime
    var retryDelay = min(retryTime + backOffDelay, retryTime * MaxRetryTimeMultiple)

    if (connectionAttemptTime != null) {
      val connectionTime = Duration.between(connectionAttemptTime, Instant.now()).toMillis()
      retryDelay = max(retryDelay - connectionTime, 0.0)
    }

    retryAttempt++

    logger.debug("Scheduling reconnect in {} ms", retryDelay)

    reconnectTimerTask =
      Timer("Reconnect", false)
        .schedule(retryDelay.toLong()) {
          runBlocking {
            internalConnect()
          }
        }
  }

  private fun clearReconnect() {
    reconnectTimerTask?.cancel()
    reconnectTimerTask = null
  }

  fun addEventListener(event: String, handler: (id: String?, event: String?, data: String?) -> Unit) {
    stateLock.write {
      eventListeners[event] = handler
    }
  }

  fun removeEventListener(event: String) {
    stateLock.write {
      eventListeners.remove(event)
    }
  }
}
