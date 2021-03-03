package io.outfoxx.sunday

import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.source
import java.io.InputStream
import kotlin.reflect.KClass

class BinaryDecoder : MediaTypeDecoder {

  override fun <T : Any> decode(data: ByteArray, type: KClass<T>): T =
    @Suppress("UNCHECKED_CAST")
    when (type) {
      ByteArray::class -> data as T
      ByteString::class -> data.toByteString(0, data.size) as T
      InputStream::class -> data.inputStream() as T
      Source::class -> data.inputStream().source() as T
      else -> error("Unsupported type for binary decode")
    }
}
