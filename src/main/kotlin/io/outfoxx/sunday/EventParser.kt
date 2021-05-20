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

import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import kotlin.text.Charsets.UTF_8

/**
 * Parses & Dispatches Server-Sent Events
 *
 * The parser supports two-modes:
 * * `push` -
 *    Received buffers are supplied to the
 *    parser as they are made available from
 *    the underlying HTTP library.
 * * `pull` -
 *    Data is read from a stream until there
 *    is no more data or an error occurs.
 */
class EventParser {

  data class EventInfo(
    var retry: String? = null,
    var event: String? = null,
    var id: String? = null,
    var data: String? = null
  ) {

    fun toEvent(origin: String) = EventSource.Event(event, id, data, origin)

  }

  private var unprocessedData: ByteString? = null

  /**
   * Pull mode processing.
   *
   * Read all of the events it can from the stream;
   * returning only when the stream is exhausted.
   *
   * @param source Stream of event data
   * @param dispatcher Handler for parsed events
   */
  fun process(source: BufferedSource, dispatcher: (EventInfo) -> Unit) {

    while (true) {

      val (eventSeparatorIdx, eventSeparatorLength) =
        findEventSeparator(source)
          ?: return

      val eventString = source.readString(eventSeparatorIdx, charSet)
      source.skip(eventSeparatorLength)

      parseAndDispatchEvent(eventString, dispatcher)
    }
  }

  /**
   * Push mode processing.
   *
   * Parses complete events that have been made available
   * up to the end of the provided data; saving any
   * unprocessed data until the next invocation.
   *
   * @param data Latest data to process
   * @param dispatcher Handler for parsed events.
   */
  fun process(data: ByteString, dispatcher: (EventInfo) -> Unit) {

    val eventStrings: List<String>

    val availableData = unprocessedData
    if (availableData != null) {
      unprocessedData = null
      val buffer = Buffer()
      buffer.write(availableData)
      buffer.write(data)
      eventStrings = extractEventStrings(buffer.readByteString())
    } else {
      eventStrings = extractEventStrings(data)
    }

    if (eventStrings.isEmpty()) {
      return
    }

    parseAndDispatchEvents(eventStrings, dispatcher)
  }

  private fun extractEventStrings(data: ByteString): List<String> {

    val eventStrings = mutableListOf<String>()

    var curData = data
    while (curData.size > 0) {

      val eventSeparatorRange = findEventSeparator(curData)
      if (eventSeparatorRange == null) {
        this.unprocessedData = curData
        break
      }

      val eventData = curData.substring(0, eventSeparatorRange.first)
      curData = curData.substring(eventSeparatorRange.second, curData.size)

      val eventString =
        try {
          eventData.string(charSet)
        } catch (x: Throwable) {
          continue
        }

      eventStrings.add(eventString)
    }

    return eventStrings
  }

  companion object {

    private val charSet = UTF_8

    private const val LF = 0x0A.toByte()
    private const val CR = 0x0D.toByte()
    private val CRLF = ByteString.of(CR, LF)

    class Peeker(source: BufferedSource) {

      var bytesPeeked = 0L
        private set

      private val source = source.peek()

      fun readByte(): Byte {
        bytesPeeked++
        return source.readByte()
      }

      fun skip(byteCount: Long) {
        bytesPeeked += byteCount
        source.skip(byteCount)
      }

      fun indexOfElement(targetBytes: ByteString): Long {
        return source.indexOfElement(targetBytes)
      }

    }

    fun findEventSeparator(source: BufferedSource): Pair<Long, Long>? {
      val peek = Peeker(source)

      while (true) {
        val nextLineSepIdx = peek.indexOfElement(CRLF)
        if (nextLineSepIdx == -1L) {
          return null
        }

        peek.skip(nextLineSepIdx)

        when (peek.readByte()) {

          // line-feed
          LF -> {
            // if next char is same,
            // we found a separator
            if (peek.readByte() == LF) {
              return peek.bytesPeeked - 2L to 2L
            }
          }

          // carriage-return
          CR -> {
            val next = peek.readByte()

            // if next char is same,
            // we found a separator
            if (next == CR) {
              return peek.bytesPeeked - 2L to 2L
            }

            // if next is line-feed, and pattern
            // repeats, we found a separator.
            if (
              next == LF &&
              peek.readByte() == CR &&
              peek.readByte() == LF
            ) {
              return peek.bytesPeeked - 4L to 4
            }
          }

          else -> error("invalid newline")
        }
      }
    }

    private fun findEventSeparator(data: ByteString): Pair<Int, Int>? {
      for (idx in 0 until data.size) {

        when (data[idx]) {

          // line-feed
          LF -> {
            // if next char is same,
            // we found a separator
            if ((data.size > idx + 1) && data[idx + 1] == LF) {
              return idx to idx + 2
            }
          }

          // carriage-return
          CR -> {
            // if next char is same,
            // we found a separator
            if (data.size > idx + 1 && data[idx + 1] == CR) {
              return idx to idx + 2
            }

            // if next is line-feed, and pattern
            // repeats, we found a separator.
            if (
              data.size > idx + 3 &&
              data[idx + 1] == LF &&
              data[idx + 2] == CR &&
              data[idx + 3] == LF
            ) {
              return idx to idx + 4
            }
          }
          else -> continue
        }
      }
      return null
    }

    private fun parseAndDispatchEvents(
      eventStrings: List<String>,
      dispatcher: (EventInfo) -> Unit
    ) {

      for (eventString in eventStrings) {
        parseAndDispatchEvent(eventString, dispatcher)
      }

    }

    private fun parseAndDispatchEvent(eventString: String, dispatcher: (EventInfo) -> Unit) {
      if (eventString.isEmpty()) {
        return
      }

      val event = parseEvent(string = eventString)

      dispatcher(event)
    }

    private val linSeparators = """\r\n|\r|\n""".toRegex()

    private fun parseEvent(string: String): EventInfo {

      val info = EventInfo()

      for (line in string.split(linSeparators)) {

        val keyValueSeparatorIdx = line.indexOf(':')
        val (key, value) =
          if (keyValueSeparatorIdx != -1) {
            line.substring(0 until keyValueSeparatorIdx) to
              line.substring(keyValueSeparatorIdx + 1 until line.length)
          } else {
            line to ""
          }

        when (key) {

          "retry" -> info.retry = trimEventField(string = value)

          "event" -> info.event = trimEventField(string = value)

          "id" -> info.id = trimEventField(string = value)

          "data" -> {
            val currentData = info.data
            if (currentData != null) {
              info.data = currentData + trimEventField(string = value) + "\n"
            } else {
              info.data = trimEventField(string = value) + "\n"
            }
          }

          // Ignore comments
          "" -> continue

          else -> continue
        }
      }

      val data = info.data
      if (data?.lastOrNull() == '\n') {
        info.data = data.dropLast(1)
      }
      return info
    }

    private fun trimEventField(string: String): String {
      if (string.firstOrNull() == ' ') {
        return string.drop(1)
      }
      return string
    }
  }

}
