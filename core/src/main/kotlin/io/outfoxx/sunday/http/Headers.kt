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

package io.outfoxx.sunday.http

/**
 * HTTP Headers.
 */
typealias Headers = Iterable<Pair<String, String>>

/**
 * Retrieves the first header matching the given name or throws an error.
 *
 * @param name Name of the header to find; names are compared case insensitively.
 * @throws NoSuchElementException Thrown when no header with the given name was found.
 */
fun Headers.getFirst(name: String): String =
  first { it.first.equals(name, ignoreCase = true) }.second

/**
 * Retrieves the first header matching the given name or returns null.
 *
 * @param name Name of the header to find; names are compared case insensitively.
 */
fun Headers.getFirstOrNull(name: String): String? =
  firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

/**
 * Retrieves all the headers matching the given name.
 *
 * @param name Name of the header values to find; names are compared case insensitively.
 * @return All values
 */
fun Headers.getAll(name: String): Iterable<String> =
  filter { it.first.equals(name, ignoreCase = true) }.map { it.second }


/**
 * Converts the header list into an equivalent multi-map.
 */
fun Headers.toMultiMap(): Map<String, List<String>> =
  groupBy({ it.first.lowercase() }, { it.second })
