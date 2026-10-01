/*
 * Copyright 2026 Outfox, Inc.
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

package io.outfoxx.sunday.validation.jakarta;

import io.outfoxx.sunday.json.patch.PatchOp;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;

/** Preserves JSON Merge Patch presence while delegating supplied values to native validation. */
@UnwrapByDefault
public final class PatchOpValueExtractor implements ValueExtractor<PatchOp<@ExtractedValue ?>> {

  @Override
  public void extractValues(PatchOp<?> originalValue, ValueReceiver receiver) {
    if (originalValue instanceof PatchOp.Set<?> set) {
      receiver.value(null, set.getValue());
    } else if (originalValue instanceof PatchOp.Delete<?>) {
      receiver.value(null, null);
    }
  }
}
