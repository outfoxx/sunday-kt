package io.outfoxx.sunday

import kotlin.reflect.KClass

interface MediaTypeDecoder {

  fun <T : Any> decode(data: ByteArray, type: KClass<T>): T

}
