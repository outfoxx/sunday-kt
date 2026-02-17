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

import io.outfoxx.sunday.EventParser
import io.outfoxx.sunday.EventParser.EventInfo
import io.outfoxx.sunday.utils.buffer
import kotlinx.io.Buffer
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.hasSize
import java.lang.Integer.min
import kotlin.random.Random

class EventParserTest {

  @Test
  fun `dispatches events with line-feeds`() {
    val eventBuffer = source("event: hello\nid: 12345\ndata: Hello World!\n\n")

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(1)
    expectThat(events)
      .containsExactlyInAnyOrder(EventInfo(null, "hello", "12345", "Hello World!"))
  }

  @Test
  fun `dispatches events with carriage-lines`() {
    val eventBuffer = source("event: hello\rid: 12345\rdata: Hello World!\r\r")

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(1)
    expectThat(events)
      .containsExactlyInAnyOrder(EventInfo(null, "hello", "12345", "Hello World!"))
  }

  @Test
  fun `dispatches events with carriage-lines, lines-feed pairs`() {
    val eventBuffer = source("event: hello\r\nid: 12345\r\ndata: Hello World!\r\n\r\n")

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(1)
    expectThat(events)
      .containsExactlyInAnyOrder(EventInfo(null, "hello", "12345", "Hello World!"))
  }

  @Test
  fun `dispatches events with mixed carriage-lines and lines-feed pairs`() {
    val eventBuffer = source("event: hello\nid: 12345\rdata: Hello World!\r\n\r\n")

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(1)
    expectThat(events)
      .containsExactlyInAnyOrder(EventInfo(null, "hello", "12345", "Hello World!"))
  }

  @Test
  fun `dispatches multiple events`() {
    val eventBuffer =
      source(
        """
        |event: hello
        |id: 12345
        |data: Hello World!
        |       
        |
        |event: hello
        |id: 67890
        |data: Hello World!
        |
        |
        |event: hello
        |id: abcde
        |data: Hello World!
        |
        |
        """.trimMargin(),
      )

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(3)
    expectThat(events).containsExactlyInAnyOrder(
      EventInfo(null, "hello", "12345", "Hello World!"),
      EventInfo(null, "hello", "67890", "Hello World!"),
      EventInfo(null, "hello", "abcde", "Hello World!"),
    )
  }

  @Test
  fun `concatenates data fields`() {
    val eventBuffer = source("event: hello\ndata: Hello \ndata: World!\n\n")

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(1)
    expectThat(events)
      .containsExactlyInAnyOrder(EventInfo(null, "hello", null, "Hello \nWorld!"))
  }

  @Test
  fun `allows empty values for fields`() {
    val eventBuffer = source("retry: \nevent: \nid: \ndata: \n\n")

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(1)
    expectThat(events).containsExactlyInAnyOrder(EventInfo("", "", "", ""))
  }

  @Test
  fun `allows empty values for fields without spaces`() {
    val eventBuffer = source("retry:\nevent:\nid:\ndata:\n\n")

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(1)
    expectThat(events).containsExactlyInAnyOrder(EventInfo("", "", "", ""))
  }

  @Test
  fun `allows empty values for fields without colons`() {
    val eventBuffer = source("retry\nevent\nid\ndata\n\n")

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(1)
    expectThat(events).containsExactlyInAnyOrder(EventInfo("", "", "", ""))
  }

  @Test
  fun `ignores comment lines`() {
    val eventBuffer = source(": this is a common\nevent\nid\ndata\n\n")

    val events = run(EventParser(), eventBuffer)

    expectThat(events).hasSize(1)
    expectThat(events).containsExactlyInAnyOrder(EventInfo(null, "", "", ""))
  }

  @Test
  fun `handles chunked messages`() {
    var eventStream =
      """
        |event: hello
        |id: 1-12345
        |data: Hello World!
        |
        |
        |event: hello
        |id: 2-12345
        |data: Hello World!
        |
        |
        |event: hello
        |id: 3-12345
        |data: Hello World!
        |
        |
        |event: hello
        |id: 4-12345
        |data: Hello World!
        |
        |
        |event: hello
        |id: 5-12345
        |data: Hello World!
        |
        |
        |
        |
        |
        |
      """.trimMargin()

    val eventBuffers = mutableListOf<kotlinx.io.Buffer>()
    while (eventStream.isNotEmpty()) {
      val sliceSize = min(Random.nextInt(10), eventStream.length)
      val slice = eventStream.substring(0 until sliceSize)
      eventBuffers.add(slice.buffer())
      eventStream = eventStream.substring(sliceSize)
    }

    val events = mutableListOf<EventInfo>()
    val parser = EventParser()
    for (eventBuffer in eventBuffers) {
      parser.process(eventBuffer) { events.add(it) }
    }

    expectThat(events).hasSize(5)
    expectThat(events).containsExactlyInAnyOrder(
      EventInfo(null, "hello", "1-12345", "Hello World!"),
      EventInfo(null, "hello", "2-12345", "Hello World!"),
      EventInfo(null, "hello", "3-12345", "Hello World!"),
      EventInfo(null, "hello", "4-12345", "Hello World!"),
      EventInfo(null, "hello", "5-12345", "Hello World!"),
    )
  }

  private fun source(data: String): kotlinx.io.Buffer = data.buffer()

  private fun run(
    parser: EventParser,
    source: kotlinx.io.Buffer,
  ): List<EventInfo> {
    val events = mutableListOf<EventInfo>()
    parser.process(source) { events.add(it) }
    return events
  }

}
