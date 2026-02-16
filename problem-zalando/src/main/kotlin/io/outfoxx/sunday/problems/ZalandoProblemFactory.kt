package io.outfoxx.sunday.problems

import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.problems.ProblemFactory.Descriptor
import org.zalando.problem.StatusType
import org.zalando.problem.ThrowableProblem
import java.net.URI
import org.zalando.problem.Problem as ZalandoProblem
import org.zalando.problem.Status as ZalandoStatus

/**
 * Problem factory backed by Zalando's `problem` library.
 *
 * This module is intended for legacy/compatibility use. It requires
 * `jackson-datatype-problem` on the classpath, and Sunday registers a
 * custom Jackson handler (see `ObjectMapperDecoder`) so `ThrowableProblem`
 * subclasses can be decoded without explicit type registration.
 */
object ZalandoProblemFactory : ProblemFactory {

  object Adapter : ProblemAdapter {

    private fun asThrowableProblem(problem: Problem): ThrowableProblem? = problem as? ThrowableProblem

    override fun getType(problem: Problem): URI = asThrowableProblem(problem)?.type ?: URI.create("about:blank")

    override fun getTitle(problem: Problem): String? = asThrowableProblem(problem)?.title

    override fun getStatus(problem: Problem): Status? =
      asThrowableProblem(problem)?.status?.let { status ->
        Status.valueOf(status.statusCode, status.reasonPhrase)
      }

    override fun getDetail(problem: Problem): String? = asThrowableProblem(problem)?.detail

    override fun getInstance(problem: Problem): URI? = asThrowableProblem(problem)?.instance

    override fun getExtensions(problem: Problem): Map<String, Any?> = asThrowableProblem(problem)?.parameters ?: mapOf()
  }

  class Builder(
    private var descriptor: Descriptor = Descriptor(),
  ) : ProblemFactory.Builder {

    override fun detail(detail: String?): Builder {
      descriptor = descriptor.copy(detail = detail)
      return this
    }

    override fun instance(uri: URI): Builder {
      descriptor = descriptor.copy(instance = uri)
      return this
    }

    override fun extension(
      name: String,
      value: Any?,
    ): Builder {
      descriptor = descriptor.copy(extensions = descriptor.extensions + (name to value))
      return this
    }

    override fun build(): Problem = fromDescriptor(descriptor)
  }

  override fun typed(type: URI): ProblemFactory.Builder = Builder(Descriptor(type = type))

  override fun from(status: Status): ProblemFactory.Builder =
    Builder(Descriptor(status = status.code, title = status.reasonPhrase))

  override fun from(descriptor: Descriptor): Problem = fromDescriptor(descriptor)

  override fun adapter(): ProblemAdapter = Adapter

  private fun fromDescriptor(descriptor: Descriptor): ThrowableProblem {
    val builder =
      ZalandoProblem
        .builder()
        .withType(descriptor.type)
        .apply {
          descriptor.title?.let(::withTitle)
          descriptor.status?.let { withStatus(toZalandoStatus(it)) }
          descriptor.detail?.let(::withDetail)
          descriptor.instance?.let(::withInstance)
          descriptor.extensions.forEach { (name, value) -> with(name, value) }
        }
    return builder.build()
  }

  private fun toZalandoStatus(statusCode: Int): StatusType {
    val status = Status.valueOf(statusCode)
    return try {
      val zalandoStatus = ZalandoStatus.valueOf(statusCode)
      if (status.reasonPhrase != null && status.reasonPhrase != zalandoStatus.reasonPhrase) {
        NonStandardStatus(status.code, status.reasonPhrase)
      } else {
        zalandoStatus
      }
    } catch (_: IllegalArgumentException) {
      NonStandardStatus(status.code, status.reasonPhrase)
    }
  }
}
