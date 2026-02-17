package io.outfoxx.sunday.problems

/**
 * A Problem is only required to be a [Throwable] to allow the [io.outfoxx.sunday.RequestFactory]
 * to transform problem responses into exceptions.
 *
 * Extension modules implement [ProblemFactory] and to build specific problem types for their framework
 * and [ProblemAdapter] to allow introspecting the problems generically.
 *
 * @see ProblemFactory
 */
typealias Problem = Throwable
