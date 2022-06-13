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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.outfoxx.sunday.json.patch.Patch
import io.outfoxx.sunday.json.patch.PatchOp
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.Base64

@JsonInclude(NON_EMPTY)
data class Security(
  var type: PatchOp<Int> = PatchOp.none(),
  var enc: PatchOp<String> = PatchOp.none(),
  var sig: PatchOp<String> = PatchOp.none(),
) : Patch {

  companion object {

    fun merge(init: Security.() -> Unit): PatchOp.Set<Security> {
      val patch = Security()
      patch.init()
      return PatchOp.Set(patch)
    }

    fun patch(init: Security.() -> Unit) = merge(init).value

  }

}


@JsonInclude(NON_EMPTY)
data class DeviceUpdate(
  var name: PatchOp<String> = PatchOp.none(),
  var security: PatchOp<Security> = PatchOp.none(),
  var data: PatchOp<Map<String, Any>> = PatchOp.none(),
) : Patch {

  companion object {

    fun merge(init: DeviceUpdate.() -> Unit): PatchOp.Set<Patch> {
      val patch = DeviceUpdate()
      patch.init()
      return PatchOp.Set(patch)
    }

    fun patch(init: DeviceUpdate.() -> Unit) = merge(init).value

  }

}


class PatchingTest {

  companion object {

    private val mapper = jacksonObjectMapper()
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
  }

  @Test
  fun simple() {

    val patch =
      DeviceUpdate.patch {
        name = set("test")
        security = Security.merge {
          type = set(17)
          enc = set(Base64.getUrlEncoder().encodeToString(byteArrayOf(1, 2, 3)))
          sig = delete()
        }
      }

    val json = mapper.writeValueAsString(patch)
    assertThat(
      json,
      equalTo("""{"name":"test","security":{"type":17,"enc":"AQID","sig":null}}""")
    )

    val encodedJSON = mapper.writeValueAsString(patch)
    assertThat(encodedJSON, equalTo(json))

    val decodedPatch = mapper.readValue<DeviceUpdate>(encodedJSON)
    assertThat(decodedPatch, equalTo(patch))
  }

}
