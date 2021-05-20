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
import io.outfoxx.sunday.EventSourceError.Reason.EventTimeout
import io.outfoxx.sunday.EventSourceError.Reason.InvalidState
import io.outfoxx.sunday.MediaType.Companion.EventStream
import io.outfoxx.sunday.http.HeaderNames.Accept
import io.outfoxx.sunday.http.HeaderNames.LastEventId
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.internal.connection.RealCall
import okhttp3.internal.toImmutableMap
import okio.BufferedSource
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
  retryTime: Duration = Duration.of(500, MILLIS),
  eventTimeout: Duration = Duration.of(75, SECONDS),
  eventTimeoutCheckInterval: Duration = Duration.of(2, SECONDS),
  private val logger: Logger = LoggerFactory.getLogger(EventSource::class.java)
) : Closeable {

  data class Event(
    val event: String?,
    val id: String?,
    val data: String?,
    val origin: String
  )

  companion object {
    private const val MaxRetryTimeMultiple = 30.0
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

  private var retryTimeValue = retryTime

  val retryTime: Duration
    get() = retryTimeValue

  private var openHandler: (() -> Unit)? = null
  private var errorHandler: ((error: Throwable?) -> Unit)? = null
  private var messageHandler: ((event: Event) -> Unit)? = null
  private var eventListenersInternal = mutableMapOf<String, (Event) -> Unit>()

  private var retryAttempt = 0
  private var currentCall: Call? = null
  private var connectionAttemptTime: Instant? = null
  private var connectionOrigin: HttpUrl? = null
  private var reconnectTimerTask: TimerTask? = null
  private var lastEventId: String? = null
  private var lastEventReceivedTime = Instant.now().plus(eventTimeout)
  private var eventTimeout: Duration? = eventTimeout
  private var eventTimeoutCheckInterval = eventTimeoutCheckInterval
  private var eventTimeoutTimerTask: TimerTask? = null

  private val eventParser = EventParser()


  /**
   * Event Listeners
   */

  var onOpen: (() -> Unit)?
    get() = stateLock.read { openHandler }
    set(value) = stateLock.write { openHandler = value }

  var onError: ((error: Throwable?) -> Unit)?
    get() = stateLock.read { errorHandler }
    set(value) = stateLock.write { errorHandler = value }

  var onMessage: ((event: Event) -> Unit)?
    get() = stateLock.read { messageHandler }
    set(value) = stateLock.write { messageHandler = value }

  val eventListeners: Map<String, (Event) -> Unit>
    get() = eventListenersInternal.toImmutableMap()

  fun addEventListener(event: String, handler: (Event) -> Unit) {
    stateLock.write {
      eventListenersInternal[event] = handler
    }
  }

  fun removeEventListener(event: String) {
    stateLock.write {
      eventListenersInternal.remove(event)
    }
  }


  /**
   * Connect
   */

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
    lastEventId?.let {
      headers = headers.plus(LastEventId to it)
    }

    connectionAttemptTime = Instant.now()

    currentCall = callSupplier(headers.toHeaders())

    currentCall?.enqueue(
      object : Callback {
        override fun onResponse(call: Call, response: Response) {
          response.use {
            if (!response.isSuccessful) {
              receivedError(null)
              return
            }

            receivedHeaders(response)

            // This is a long-lived response. Cancel full-call timeouts.
            (call as? RealCall)?.timeoutEarlyExit()

            val body = response.body
            if (body != null) {
              receivedData(body.source())
            }

            receivedComplete()
          }
        }

        override fun onFailure(call: Call, e: IOException) {
          receivedError(e)
        }

      },
    )
  }


  /**
   * Connect
   */

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


  /**
   * Event Timeout
   */

  private fun startEventTimeoutCheck(lastEventReceivedTime: Instant) {
    stopEventTimeoutCheck()
    eventTimeout ?: return

    this.lastEventReceivedTime = lastEventReceivedTime

    scheduleEventTimeoutCheck()
  }

  private fun stopEventTimeoutCheck() {

    eventTimeoutTimerTask?.cancel()
    eventTimeoutTimerTask = null
  }

  private fun checkEventTimeout() {
    val eventTimeout = this.eventTimeout ?: return

    logger.debug("Checking event timeout")

    // Check elapsed time since last received event
    val deadline = lastEventReceivedTime.plus(eventTimeout)
    val now = Instant.now()
    if (now.isBefore(deadline)) {
      logger.debug("Event timeout has not expired: now={}, deadline={}", now, deadline)
      // check again
      scheduleEventTimeoutCheck()
      return
    }

    logger.debug("Event timeout deadline expired")

    errorHandler?.invoke(EventSourceError(EventTimeout))

    stopEventTimeoutCheck()
    scheduleReconnect()
  }

  private fun scheduleEventTimeoutCheck() {
    eventTimeoutTimerTask?.cancel()
    eventTimeoutTimerTask =
      Timer("Event Timeout")
        .schedule(eventTimeoutCheckInterval.toMillis()) {
          checkEventTimeout()
        }
  }


  /**
   * Connection Handlers
   */

  private fun receivedHeaders(response: Response) {
    if (readyStateValue != Connecting) {
      logger.warn("Invalid state for receiving headers: {}", readyStateValue)

      errorHandler?.invoke(EventSourceError(InvalidState))

      scheduleReconnect()
      return
    }

    connectionOrigin = response.request.url
    retryAttempt = 0
    readyStateValue = Open

    // Start event timeout check, treating this
    // connect as last time we received an event
    startEventTimeoutCheck(Instant.now())

    logger.debug("Opened")

    openHandler?.invoke()
  }

  private fun receivedData(source: BufferedSource) {
    if (readyStateValue != Open) {
      logger.warn("Invalid state for receiving headers: {}", readyStateValue)

      errorHandler?.invoke(EventSourceError(InvalidState))

      scheduleReconnect()
      return
    }

    logger.debug("Starting event processing")

    eventParser.process(source, ::dispatchParsedEvent)
  }

  private fun receivedError(t: Throwable?) {
    if (readyStateValue == Closed) {
      return
    }

    logger.debug("Received error", t)

    errorHandler?.invoke(t)

    if (readyStateValue != Closed) {
      scheduleReconnect()
    }
  }

  private fun receivedComplete() {
    if (readyStateValue == Closed) {
      return
    }

    logger.debug("Received complete")

    scheduleReconnect()
  }


  /**
   * Reconnection
   */

  private fun scheduleReconnect() {
    internalClose()

    val lastConnectTime =
      if (connectionAttemptTime != null) {
        Duration.between(connectionAttemptTime, Instant.now())
      } else {
        Duration.ZERO
      }

    val retryDelay = calculateRetryDelay(retryAttempt, retryTime, lastConnectTime)

    logger.debug("Scheduling reconnect in {}", retryDelay)

    retryAttempt++
    readyStateValue = Connecting

    reconnectTimerTask =
      Timer("Reconnect", false)
        .schedule(retryDelay.toMillis()) {
          runBlocking {
            internalConnect()
          }
        }
  }

  private fun clearReconnect() {
    reconnectTimerTask?.cancel()
    reconnectTimerTask = null
  }

  private fun calculateRetryDelay(
    retryAttempt: Int,
    retryTime: Duration,
    lastConnectTime: Duration
  ): Duration {

    val retryTimeMs = retryTime.toMillis()

    // calculate total delay
    val backOffDelayMs = retryAttempt.toDouble().pow(2) * retryTimeMs
    var retryDelayMs =
      min(
        retryTimeMs + backOffDelayMs,
        retryTimeMs * MaxRetryTimeMultiple
      )

    // Adjust delay by amount of time last connect
    // cycle took, except on the first attempt
    if (retryAttempt > 0) {

      retryDelayMs -= lastConnectTime.toMillis()

      // Ensure delay is at least as large as
      // minimum retry time interval
      retryDelayMs = max(retryDelayMs, retryTimeMs.toDouble())
    }

    return Duration.ofMillis(retryDelayMs.toLong())
  }


  /**
   * Event Dispatch
   */

  private fun dispatchParsedEvent(info: EventParser.EventInfo) {

    lastEventReceivedTime = Instant.now()

    // Update retry time if it's a valid integer
    val retry = info.retry
    if (retry != null) {

      val retryTime = retry.trim().toLongOrNull(10)
      if (retryTime != null) {

        logger.debug("update retry timeout: retryTime=$retryTime")

        this.retryTimeValue = Duration.ofMillis(retryTime)

      } else {
        logger.debug("ignoring invalid retry timeout message: retry=$retry")
      }

    }

    // Skip events without data
    if (info.event == null && info.id == null && info.data == null) {
      // Skip empty events
      return
    }

    // Save event id, if it does not contain null
    val eventId = info.id
    if (eventId != null) {

      // Check for NULL as it is not allowed
      if (!eventId.contains(0.toChar())) {

        lastEventId = eventId

      } else {
        logger.debug("event id contains null, unable to use for last-event-id")
      }
    }

    val event = info.toEvent(connectionOrigin?.toString() ?: "")

    val messageHandler = messageHandler
    if (messageHandler != null) {
      logger.debug("dispatch onMessage: event=${info.event ?: ""}, id=${info.id ?: ""}")

      messageHandler(event)
    }

    val eventHandler = eventListeners[info.event ?: ""]
    if (eventHandler != null) {
      logger.debug("dispatch listener: event=${info.event ?: ""}, id=${info.id ?: ""}")

      eventHandler(event)
    }
  }

}
