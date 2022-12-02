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

package io.outfoxx.sunday.okhttp

import io.outfoxx.sunday.RequestFactory
import io.outfoxx.sunday.URITemplate
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeDecoders
import io.outfoxx.sunday.mediatypes.codecs.MediaTypeEncoders
import io.outfoxx.sunday.test.Implementation
import io.outfoxx.sunday.test.RequestFactoryTest

class OkHttpRequestFactoryTest : RequestFactoryTest() {

  override val implementation = Implementation.OkHttp

  override fun createRequestFactory(
    uriTemplate: URITemplate,
    encoders: MediaTypeEncoders,
    decoders: MediaTypeDecoders
  ): RequestFactory =
    OkHttpRequestFactory(
      uriTemplate,
      mediaTypeEncoders = encoders,
      mediaTypeDecoders = decoders,
    )

}
