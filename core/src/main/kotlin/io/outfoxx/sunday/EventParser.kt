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
import org.slf4j.LoggerFactory
import kotlin.text.Charsets.UTF_8

/**
 * Parses & Dispatches Server-Sent Events.
 */
class EventParser {

  // Dispatched event information.
  data class EventInfo(
    /**
     * SSE retry field.
     */
    var retry: String? = null,
    /**
     * SSE event field.
     */
    var event: String? = null,
    /**
     * SSE id field.
     */
    var id: String? = null,
    /**
     * SSE data field.
     */
    var data: String? = null,
  ) {

    fun toEvent(origin: String) = EventSource.Event(event, id, data, origin)

  }

  private val unprocessedData = Buffer()

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
  fun process(
    data: Buffer,
    dispatcher: (EventInfo) -> Unit,
  ) {
    unprocessedData.write(data, data.size)

    val eventStrings = extractEventStrings()
    if (eventStrings.isEmpty()) {
      return
    }

    parseAndDispatchEvents(eventStrings, dispatcher)
  }

  @Suppress("LoopWithTooManyJumpStatements")
  private fun extractEventStrings(): List<String> {
    val eventStrings = mutableListOf<String>()

    while (!unprocessedData.exhausted()) {
      val eventSeparatorRange =
        findEventSeparator(unprocessedData)
          ?: break

      val eventData = unprocessedData.readByteString(eventSeparatorRange.first)
      unprocessedData.skip(eventSeparatorRange.second - eventSeparatorRange.first)

      val eventString =
        try {
          eventData.string(charSet)
        } catch (t: Throwable) {
          logger.warn("Error reading event data", t)
          continue
        }

      eventStrings.add(eventString)
    }

    return eventStrings
  }

  companion object {

    private val logger = LoggerFactory.getLogger(EventParser::class.java)

    private val charSet = UTF_8

    private const val LF = 0x0A.toByte()
    private const val CR = 0x0D.toByte()

    private fun findEventSeparator(data: Buffer): Pair<Long, Long>? {
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
      dispatcher: (EventInfo) -> Unit,
    ) {
      for (eventString in eventStrings) {
        parseAndDispatchEvent(eventString, dispatcher)
      }

    }

    private fun parseAndDispatchEvent(
      eventString: String,
      dispatcher: (EventInfo) -> Unit,
    ) {
      if (eventString.isEmpty()) {
        return
      }

      val event = parseEvent(string = eventString)

      dispatcher(event)
    }

    private val linSeparators = """\r\n|\r|\n""".toRegex()

    @Suppress("LoopWithTooManyJumpStatements")
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
