package io.outfoxx.sunday

import io.outfoxx.sunday.MediaType.StandardParameterName.CharSet
import java.nio.charset.Charset

fun Charsets.from(mediaType: MediaType, default: Charset = UTF_8): Charset {

  val encoding = mediaType.parameter(CharSet) ?: return default

  return Charset.forName(encoding)
}
