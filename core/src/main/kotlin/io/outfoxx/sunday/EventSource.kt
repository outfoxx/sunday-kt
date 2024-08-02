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
import io.outfoxx.sunday.http.Headers
import io.outfoxx.sunday.http.Request
import io.outfoxx.sunday.http.Response
import io.outfoxx.sunday.http.isSuccessful
import io.outfoxx.sunday.utils.Problems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.lang.Double.max
import java.lang.Double.min
import java.net.SocketException
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.concurrent.write
import kotlin.math.pow

/**
 * Server-Sent Events stream client.
 *
 * [EventSource] is built on Sunday requests and allows customizing the
 * requests for scenarios like authentication and custom headers.
 */
class EventSource(
  private val requestSupplier: suspend (Headers) -> Request,
  retryTime: Duration = retryTimeDefault,
  private val eventTimeout: Duration? = eventTimeoutDefault,
  private val eventTimeoutCheckInterval: Duration = eventTimeoutCheckIntervalDefault,
  private val logger: Logger = LoggerFactory.getLogger(EventSource::class.java),
) : Closeable {

  /**
   * SSE Event.
   */
  data class Event(
    /**
     * SSE event field.
     */
    val event: String?,
    /**
     * SSE id field.
     */
    val id: String?,
    /**
     * SSE data field.
     */
    val data: String?,
    /**
     * SSE origin.
     */
    val origin: String,
  )

  companion object {

    /***
     * Global default time interval for connection retries.
     *
     * @see [EventSource.retryTime]
     */
    var retryTimeDefault: Duration = Duration.of(500, ChronoUnit.MILLIS)

    /**
     * Global default time interval for event timeout.
     *
     * If an event is not received within the specified timeout the connection is
     * forcibly restarted. If set to null, the default will be that event timeouts are disabled.
     *
     * Each [EventSource] can override this setting in its constructor using the `eventTimeout`
     * constructor parameter.
     */
    var eventTimeoutDefault: Duration = Duration.of(75, ChronoUnit.SECONDS)

    /**
     * Global default time interval for event timeout checks.
     *
     * This setting controls the frequency that the event timeout is checked.
     *
     * Each [EventSource] can override this setting in its constructor using the
     * `eventTimeoutCheckInterval`.
     */
    var eventTimeoutCheckIntervalDefault: Duration = Duration.of(2, ChronoUnit.SECONDS)

    /**
     * Global default read timeout for [EventSource] http clients.
     *
     * If data is not received without the read timeout, a cancellation error
     * will cause a reconnection attempt to be initiated.
     *
     * Note: [EventSource]s can override the HTTP read timeout by customizing the `httpClient`
     * constructor parameters. This default applies to instances that use a system create
     * client.
     */
    var httpReadTimeoutDefault: Duration = Duration.ofMinutes(10)

    private fun createRequestEventScope(): CoroutineScope =
      CoroutineScope(CoroutineName("EventSource - Request Processor"))

    private const val MAX_RETRY_TIME_MULTIPLE = 30.0
  }

  /**
   * Possible states of the [EventSource].
   */
  enum class ReadyState {

    /**
     * [EventSource] is connecting.
     */
    Connecting,

    /**
     * [EventSource] is open.
     */
    Open,

    /**
     * [EventSource] is closed.
     */
    Closed,
  }

  private val stateLock = ReentrantReadWriteLock()

  /**
   * Current state of the [EventSource].
   */
  val readyState: ReadyState
    get() = readyStateValue.current
  private var readyStateValue = ReadyStateValue()

  /**
   * Current retry time.
   *
   * The retry time can be provided in [EventSource] constructor but may be
   * updated by the server after connection and as it processes events.
   */
  val retryTime: Duration
    get() = retryTimeValue
  private var retryTimeValue = retryTime

  private var openHandler: (() -> Unit)? = null
  private var errorHandler: ((error: Throwable?) -> Unit)? = null
  private var messageHandler: ((event: Event) -> Unit)? = null
  private var eventListenersInternal = mutableMapOf<String, (Event) -> Unit>()

  private var retryAttempt = 0
  private var currentRequest: Job? = null
  private var connectionAttemptTime: Instant? = null
  private var connectionOrigin: URI? = null
  private var reconnectTimerTask: TimerTask? = null
  private var lastEventId: String? = null
  private var lastEventReceivedTime = Instant.MAX
  private var eventTimeoutTimerTask: TimerTask? = null

  private val eventParser = EventParser()


  /*
   * Event Listening
   */


  /**
   * Open handler.
   *
   * Handler is called whenever the [EventSource] is connected,
   * including after reconnects.
   */
  var onOpen: (() -> Unit)?
    get() = stateLock.read { openHandler }
    set(value) = stateLock.write { openHandler = value }

  /**
   * Error handler.
   *
   * Handler is called whenever a connection error is encountered.
   */
  var onError: ((error: Throwable?) -> Unit)?
    get() = stateLock.read { errorHandler }
    set(value) = stateLock.write { errorHandler = value }

  /**
   * Message handler.
   *
   * Handler is called for all events received, regardless of type.
   */
  var onMessage: ((event: Event) -> Unit)?
    get() = stateLock.read { messageHandler }
    set(value) = stateLock.write { messageHandler = value }

  /**
   * Current list of event listeners.
   */
  val eventListeners: Map<String, (Event) -> Unit>
    get() = stateLock.read { eventListenersInternal.toMap() }

  /**
   * Add an event listener for a specific event type.
   */
  fun addEventListener(
    event: String,
    handler: (Event) -> Unit,
  ) {
    stateLock.write { eventListenersInternal[event] = handler }
  }

  /**
   * Removed a previously added event listener.
   */
  fun removeEventListener(
    event: String,
    handler: (Event) -> Unit,
  ) {
    stateLock.write { eventListenersInternal.remove(event, handler) }
  }


  /*
   * Connection Management
   */


  /**
   * Connect the [EventSource].
   *
   * [connect] is idempotent and redundant calls will be ignored. Additionally, the
   * [EventSource] will remain connected, including reconnecting when necessary, until
   * [close] is called.
   */
  fun connect() {
    if (readyStateValue.isNotClosed) {
      return
    }

    readyStateValue.resetReadyState(Connecting)

    runBlocking {
      internalConnect()
    }
  }

  private suspend fun internalConnect() {
    if (readyStateValue.isClosed) {
      logger.debug("Skipping connect due to close")
      return
    }

    logger.debug("Connecting")

    // Build default headers for passing to request builder

    var headers =
      listOf(
        Accept to EventStream.value,
      )

    // Add last-event-id if we are reconnecting

    lastEventId?.let {
      headers = headers.plus(LastEventId to it)
    }

    connectionAttemptTime = Instant.now()

    val request = requestSupplier(headers)

    currentRequest =
      createRequestEventScope().launch {
        try {
          request
            .start()
            .onCompletion {
              if (it != null) {
                receivedError(it)
              } else {
                receivedComplete()
              }
            }.collect(::dispatchEvent)

        } catch (ignored: CancellationException) {
          // do nothing
        } catch (error: Throwable) {
          receivedError(error)
        }
      }
  }

  private fun dispatchEvent(event: Request.Event) {
    if (readyStateValue.isClosed) {
      return
    }

    when (event) {
      is Request.Event.Start -> {
        if (!event.value.isSuccessful) {
          receivedError(Problems.forResponse(event.value))
        }

        receivedResponse(event.value)
      }

      is Request.Event.Data ->
        receivedData(event.value)

      is Request.Event.End ->
        receivedComplete()
    }
  }

  /**
   * Close and disconnect the [EventSource].
   */
  override fun close() {
    if (readyStateValue.isClosed) {
      return
    }

    logger.debug("Closed")

    readyStateValue.resetReadyState(Closed)

    internalClose()
  }

  private fun internalClose() {
    currentRequest?.cancel(null)

    clearReconnect()

    stopEventTimeoutCheck()
  }


  /**
   * Event Timeout Management
   */


  private fun startEventTimeoutCheck(lastEventReceivedTime: Instant) {
    stopEventTimeoutCheck()
    eventTimeout ?: return

    this.lastEventReceivedTime = lastEventReceivedTime

    eventTimeoutTimerTask?.cancel()
    eventTimeoutTimerTask =
      Timer("Event Timeout", true)
        .scheduleAtFixedRate(
          eventTimeoutCheckInterval.toMillis(),
          eventTimeoutCheckInterval.toMillis(),
        ) {
          checkEventTimeout()
        }
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
      return
    }

    logger.debug("Event timeout deadline expired")

    stateLock.read { errorHandler }?.invoke(EventSourceError(EventTimeout))

    stopEventTimeoutCheck()
    scheduleReconnect()
  }


  /**
   * Connection Event Handlers
   */


  private fun receivedResponse(response: Response) {
    if (!readyStateValue.updateIfNotClosed(Open)) {
      logger.warn("Invalid state for receiving headers: {}", readyStateValue.current)

      stateLock.read { errorHandler }?.invoke(EventSourceError(InvalidState))

      scheduleReconnect()
      return
    }

    logger.debug("Opened")

    connectionOrigin = response.request.uri
    retryAttempt = 0

    // Start event timeout check, treating this
    // connection as last time we received an event
    startEventTimeoutCheck(Instant.now())

    stateLock.read { openHandler }?.invoke()
  }

  private fun receivedData(data: Buffer) {
    if (readyStateValue.current != Open) {
      logger.warn("Invalid state for receiving headers: {}", readyStateValue.current)

      stateLock.read { errorHandler }?.invoke(EventSourceError(InvalidState))

      scheduleReconnect()
      return
    }

    logger.debug("Received: data")

    try {
      eventParser.process(data, ::dispatchParsedEvent)

    } catch (x: SocketException) {
      if (readyStateValue.isClosed) {
        return
      } else {
        throw x
      }
    }
  }

  private fun receivedError(t: Throwable?) {
    if (readyStateValue.isClosed) {
      return
    }

    logger.error("Received: error", t)

    stateLock.read { errorHandler }?.invoke(t)

    if (readyStateValue.isNotClosed) {
      scheduleReconnect()
    }
  }

  private fun receivedComplete() {
    if (readyStateValue.isClosed) {
      return
    }

    logger.debug("Received: complete")

    scheduleReconnect()
  }


  /**
   * Reconnection Management
   */


  private fun scheduleReconnect() {
    if (readyStateValue.isClosed) {
      return
    }

    internalClose()

    if (!readyStateValue.updateIfNotClosed(Connecting)) {
      return
    }

    val lastConnectTime =
      if (connectionAttemptTime != null) {
        Duration.between(connectionAttemptTime, Instant.now())
      } else {
        Duration.ZERO
      }

    val retryDelay = calculateRetryDelay(retryAttempt, retryTime, lastConnectTime)

    logger.debug("Scheduling reconnect in {}", retryDelay)

    retryAttempt++

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
    lastConnectTime: Duration,
  ): Duration {
    val retryTimeMs = retryTime.toMillis()

    // calculate total delay
    val backOffDelayMs = retryAttempt.toDouble().pow(2) * retryTimeMs
    var retryDelayMs =
      min(
        retryTimeMs + backOffDelayMs,
        retryTimeMs * MAX_RETRY_TIME_MULTIPLE,
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
   * Event Dispatching
   */


  private fun dispatchParsedEvent(info: EventParser.EventInfo) {
    lastEventReceivedTime = Instant.now()

    // Update retry time if it's a valid integer
    val retry = info.retry
    if (retry != null) {
      val retryTime = retry.trim().toLongOrNull(radix = 10)
      if (retryTime != null) {
        logger.debug("update retry timeout: retryTime=$retryTime")

        this.retryTimeValue = Duration.ofMillis(retryTime)
      } else {
        logger.warn("ignoring invalid retry timeout message: retry=$retry")
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
        logger.warn("event id contains null, unable to use for last-event-id")
      }
    }

    val event = info.toEvent(connectionOrigin?.toString() ?: "")

    stateLock.read { messageHandler }?.let { handler ->

      logger.debug("dispatch onMessage: event=${info.event ?: ""}, id=${info.id ?: ""}")

      handler(event)
    }

    eventListeners[info.event ?: ""]?.let { handler ->

      logger.debug("dispatch listener: event=${info.event ?: ""}, id=${info.id ?: ""}")

      handler(event)
    }
  }

  /**
   * Ready state value manager that ensures concurrent
   * access and reduces the chance of the event source
   * automatically re-opening.
   */
  private class ReadyStateValue {

    private var currentValue = Closed
    private val lock = ReentrantReadWriteLock()

    val current: ReadyState get() = lock.read { currentValue }
    val isClosed: Boolean get() = lock.read { currentValue == Closed }
    val isNotClosed: Boolean get() = lock.read { currentValue != Closed }

    fun updateIfNotClosed(newValue: ReadyState): Boolean {
      lock.write {
        if (currentValue == Closed) {
          return false
        }

        currentValue = newValue

        return true
      }
    }

    fun resetReadyState(newValue: ReadyState) {
      lock.write {
        currentValue = newValue
      }
    }
  }
}
