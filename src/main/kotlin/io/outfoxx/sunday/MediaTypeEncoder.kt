package io.outfoxx.sunday

interface MediaTypeEncoder {

  fun <T> encode(value: T): ByteArray

}
