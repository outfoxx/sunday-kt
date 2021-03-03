package io.outfoxx.sunday

import okio.Source
import okio.buffer
import java.io.InputStream
import kotlin.reflect.jvm.internal.impl.protobuf.ByteString

class BinaryEncoder : MediaTypeEncoder {

  override fun <B> encode(value: B): ByteArray =
    when (value) {
      is ByteArray -> value
      is ByteString -> value.toByteArray()
      is InputStream -> value.use { it.readAllBytes() }
      is Source -> value.use { it.buffer().readByteArray() }
      else -> error("Unsupported value for binary encode")
    }
}
