package io.outfoxx.sunday

import kotlin.reflect.KClass

interface TextMediaTypeDecoder : MediaTypeDecoder {

  fun <T : Any> decode(data: String, type: KClass<T>): T

}
