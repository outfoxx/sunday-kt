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
 * HTTP Request Method.
 *
 * Many of the common HTTP methods are provided as constants while the [Method]
 * constructor can be used to create unsupported or custom methods.
 *
 * Constants are provided for the common methods
 * [Options], [Get], [Head], [Post], [Put], [Patch], [Delete], [Trace], and [Connect].
 */
data class Method(
  val name: String,
  val requiresBody: Boolean = false,
) {

  companion object {

    val Options = Method("OPTIONS")
    val Get = Method("GET")
    val Head = Method("HEAD")
    val Post = Method("POST", true)
    val Put = Method("PUT", true)
    val Patch = Method("PATCH", true)
    val Delete = Method("DELETE")
    val Trace = Method("TRACE")
    val Connect = Method("CONNECT")

    fun values() = listOf(Options, Get, Head, Post, Put, Patch, Delete, Trace, Connect)

    fun valueOf(name: String): Method =
      values().firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: Method(name, false)
  }
}
